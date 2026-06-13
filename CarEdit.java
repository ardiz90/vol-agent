import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

/**
 * CarEdit — SDK/CLI di modifica per cartridge Volante Designer (.car).
 *
 * Filosofia: l'agente (o l'umano) decide COSA cambiare, il tool decide COME:
 * genera gli UUID, ricabla i link, preserva la formattazione esistente facendo
 * edit chirurgici a livello di riga, e valida il risultato in memoria PRIMA di
 * scrivere su disco. Se la validazione fallisce, il file originale resta intatto.
 *
 * Comandi:
 *   java CarEdit.java <file.car> list
 *   java CarEdit.java <file.car> insert-between <nodoA> <nodoB> [--type Custom]
 *        [--name NomeNodo] [--label "Etichetta"] [--code "..."| --code-file f]
 *   java CarEdit.java <file.car> set-code  <nodo> (--code "..." | --code-file f)
 *   java CarEdit.java <file.car> set-label <nodo> --label "Etichetta"
 *   java CarEdit.java <file.car> wrap-nullcheck <nodo>
 *        --lib <lib.car> --message <rulesname> --path <a.b.c> [--base var]
 *
 * I nodi si indicano per name (es. Custom3) o per prefisso univoco di uid.
 * insert-between assume che il nuovo nodo abbia una sola uscita (port 1):
 * va bene per Custom; non usarlo per inserire If/Validate.
 *
 * wrap-nullcheck e' OPT-IN: avvolge il codice esistente di un nodo Custom in una
 * guardia isNotNull(...) per i soli segmenti opzionali del path, leggendo la
 * definizione del campo dal cartridge-libreria (stessa logica di CarSchema). Se
 * tutti i segmenti sono mandatory non modifica nulla. Gli altri comandi non
 * aggiungono mai null-check da soli.
 */
public class CarEdit {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); }
        Path file = Path.of(args[0]);
        String command = args[1];
        Map<String, String> opts = parseOpts(args, 2);
        List<String> positional = parsePositional(args, 2);

        CarFile car = CarFile.load(file);
        switch (command) {
            case "list":
                car.printGraph();
                break;
            case "insert-between": {
                require(positional.size() >= 2, "insert-between richiede <nodoA> <nodoB>");
                String code = readCodeOpt(opts);
                car.insertBetween(positional.get(0), positional.get(1),
                        opts.getOrDefault("type", "Custom"), opts.get("name"),
                        opts.getOrDefault("label", "TODO label"),
                        code == null ? "" : code);
                car.save();
                break;
            }
            case "set-code": {
                require(positional.size() >= 1, "set-code richiede <nodo>");
                String code = readCodeOpt(opts);
                require(code != null, "set-code richiede --code o --code-file");
                car.setCode(positional.get(0), code);
                car.save();
                break;
            }
            case "set-label": {
                require(positional.size() >= 1 && opts.containsKey("label"),
                        "set-label richiede <nodo> --label \"...\"");
                car.setLabel(positional.get(0), opts.get("label"));
                car.save();
                break;
            }
            case "wrap-nullcheck": {
                require(positional.size() >= 1, "wrap-nullcheck richiede <nodo>");
                require(opts.containsKey("lib") && opts.containsKey("message") && opts.containsKey("path"),
                        "wrap-nullcheck richiede --lib <lib.car> --message <msg> --path <a.b.c>");
                boolean changed = car.wrapNullCheck(positional.get(0), Path.of(opts.get("lib")),
                        opts.get("message"), opts.get("path"), opts.getOrDefault("base", "msg"));
                if (changed) car.save();
                break;
            }
            default:
                usage();
                System.exit(2);
        }
    }

    static void usage() {
        System.err.println("Uso: java CarEdit.java <file.car> <list|insert-between|set-code|set-label> ...");
        System.err.println("Vedi il commento in testa al sorgente per i dettagli.");
    }

    static void require(boolean cond, String msg) {
        if (!cond) { System.err.println("Errore: " + msg); System.exit(2); }
    }

    static String readCodeOpt(Map<String, String> opts) throws Exception {
        if (opts.containsKey("code-file")) {
            return Files.readString(Path.of(opts.get("code-file")), StandardCharsets.UTF_8)
                        .stripTrailing();
        }
        return opts.get("code");
    }

    static Map<String, String> parseOpts(String[] args, int from) {
        Map<String, String> opts = new HashMap<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String val = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "";
                opts.put(key, val);
            }
        }
        return opts;
    }

    static List<String> parsePositional(String[] args, int from) {
        List<String> pos = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("--")) { i++; continue; }
            pos.add(args[i]);
        }
        return pos;
    }
}

/** Modello di un file .car: DOM annotato con righe + testo originale per edit chirurgici. */
class CarFile {
    static final String START_LINE = "carline.start", END_LINE = "carline.end";
    static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    final Path path;
    final String eol;
    final boolean hadBom, endedWithEol;
    List<String> lines;          // 0-based; i numeri riga del DOM sono 1-based
    Document doc;

    private CarFile(Path path, String raw) {
        this.path = path;
        this.hadBom = raw.startsWith("﻿");
        if (hadBom) raw = raw.substring(1);
        this.eol = raw.contains("\r\n") ? "\r\n" : "\n";
        this.endedWithEol = raw.endsWith("\n");
        this.lines = new ArrayList<>(List.of(raw.split("\r?\n", -1)));
        if (endedWithEol && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    static CarFile load(Path path) throws Exception {
        CarFile car = new CarFile(path, Files.readString(path, StandardCharsets.UTF_8));
        car.doc = parse(car.text());
        return car;
    }

    String text() {
        return String.join(eol, lines) + (endedWithEol ? eol : "");
    }

    // ---- comandi -------------------------------------------------------------

    void printGraph() {
        for (Element flow : tags(doc, "messageflow")) {
            System.out.println("messageflow " + flow.getAttribute("name"));
            Map<String, Element> byUid = new HashMap<>();
            for (Element fe : children(flow, "flowelement")) {
                byUid.put(fe.getAttribute("uid"), fe);
                System.out.printf("  nodo  %-12s %-10s uid=%s  riga %d  \"%s\"%n",
                        fe.getAttribute("name"), fe.getAttribute("type"),
                        shortUid(fe.getAttribute("uid")), startLine(fe), childText(fe, "label"));
            }
            for (Element link : children(flow, "link")) {
                Element op = firstChild(link, "outputport"), ip = firstChild(link, "inputport");
                if (op == null || ip == null) continue;
                Element a = byUid.get(op.getAttribute("uid")), b = byUid.get(ip.getAttribute("uid"));
                System.out.printf("  link  %s[port %s] -> %s[port %s]  (%s, riga %d)%n",
                        a == null ? "?" : a.getAttribute("name"), op.getAttribute("portindex"),
                        b == null ? "?" : b.getAttribute("name"), ip.getAttribute("portindex"),
                        link.getAttribute("type"), startLine(link));
            }
        }
    }

    /** Inserisce un nuovo nodo tra A e B ricablando il link esistente A->B. */
    void insertBetween(String refA, String refB, String type, String name,
                       String label, String code) throws Exception {
        Element flow = singleFlow();
        Element a = resolveNode(flow, refA), b = resolveNode(flow, refB);
        String aUid = a.getAttribute("uid"), bUid = b.getAttribute("uid");

        // 1. trova il link A->B da ricablare
        Element targetLink = null;
        for (Element link : children(flow, "link")) {
            Element op = firstChild(link, "outputport"), ip = firstChild(link, "inputport");
            if (op != null && ip != null
                    && aUid.equals(op.getAttribute("uid")) && bUid.equals(ip.getAttribute("uid"))) {
                if (targetLink != null) {
                    throw new IllegalStateException("Più di un link tra " + refA + " e " + refB
                            + ": ricablaggio ambiguo, intervenire manualmente");
                }
                targetLink = link;
            }
        }
        if (targetLink == null) {
            throw new IllegalStateException("Nessun link diretto tra " + refA + " e " + refB);
        }
        String inPortIdx = firstChild(targetLink, "inputport").getAttribute("portindex");

        // 2. genera identità nuove e uniche
        Set<String> usedUids = allUids(), usedNames = allNames(flow);
        String newUid = freshUuid(usedUids);
        String newLinkUid = freshUuid(usedUids);
        String newName = (name != null && !name.isEmpty()) ? name : freshName(type, usedNames);
        if (usedNames.contains(newName)) {
            throw new IllegalStateException("name \"" + newName + "\" già in uso nel flow");
        }

        // 3. costruisce i blocchi XML imitando lo stile del Designer
        String feIndent = indentOf(startLine(children(flow, "flowelement").get(0)));
        String in2 = feIndent + "    ";
        List<String> feBlock = new ArrayList<>(List.of(
            feIndent + "<flowelement name=\"" + newName + "\" uid=\"" + newUid + "\" type=\""
                     + type + "\" xsi:type=\"" + type + "\">",
            in2 + "<label>" + escapeXml(label) + "</label>",
            in2 + "<description><![CDATA[]]></description>"));
        if ("Custom".equals(type)) {
            feBlock.add(in2 + "<code><![CDATA[" + code + "]]></code>");
        }
        feBlock.add(feIndent + "</flowelement>");

        List<Element> links = children(flow, "link");
        String linkIndent = indentOf(startLine(links.get(0)));
        String lin2 = linkIndent + "    ";
        List<String> linkBlock = List.of(
            linkIndent + "<link type=\"Default\" xsi:type=\"Default\" uid=\"" + newLinkUid + "\">",
            lin2 + "<outputport uid=\"" + newUid + "\" portindex=\"1\"/>",
            lin2 + "<inputport uid=\"" + bUid + "\" portindex=\"" + inPortIdx + "\"/>",
            lin2 + "<edge-type>DIRECT</edge-type>",
            linkIndent + "</link>");

        // 4. applica gli edit dal basso verso l'alto (i numeri di riga restano validi)
        List<Runnable> edits = new ArrayList<>();
        int newLinkAt = endLine(links.get(links.size() - 1));        // dopo l'ultimo link
        int rewireFrom = startLine(targetLink), rewireTo = endLine(targetLink);
        int newNodeAt = startLine(links.get(0)) - 1;                 // prima del primo link

        insertAfter(newLinkAt, linkBlock);
        replaceInRange(rewireFrom, rewireTo,
                "inputport uid=\"" + Pattern.quote(bUid) + "\"",
                "inputport uid=\"" + newUid + "\"");
        insertAfter(newNodeAt, feBlock);

        validateAndReport("insert-between",
                "nuovo nodo " + newName + " (uid " + newUid + ") inserito tra "
                + a.getAttribute("name") + " e " + b.getAttribute("name")
                + "; link " + shortUid(targetLink.getAttribute("uid"))
                + " ricablato, nuovo link " + shortUid(newLinkUid));
    }

    /** Sostituisce il contenuto CDATA dell'elemento <code> di un nodo Custom. */
    void setCode(String ref, String code) throws Exception {
        Element node = resolveNode(singleFlow(), ref);
        Element codeEl = firstChild(node, "code");
        if (codeEl == null) {
            throw new IllegalStateException(node.getAttribute("name")
                    + " (" + node.getAttribute("type") + ") non ha un elemento <code>");
        }
        replaceRange(startLine(codeEl), endLine(codeEl),
                codeBlock("code", indentOf(startLine(codeEl)), code));
        validateAndReport("set-code", "codice di " + node.getAttribute("name") + " sostituito");
    }

    /**
     * Avvolge il codice esistente di un nodo Custom in una guardia isNotNull(...)
     * per i segmenti opzionali del path. Ritorna true se ha modificato il file.
     */
    boolean wrapNullCheck(String ref, Path lib, String message, String path, String base)
            throws Exception {
        Element node = resolveNode(singleFlow(), ref);
        Element codeEl = firstChild(node, "code");
        if (codeEl == null) {
            throw new IllegalStateException(node.getAttribute("name")
                    + " (" + node.getAttribute("type") + ") non ha un elemento <code>");
        }

        List<String> guards = Schema.guards(lib, message, path, base);
        if (guards.isEmpty()) {
            System.out.println("[OK] wrap-nullcheck: tutti i segmenti di \"" + path
                    + "\" sono mandatory in " + message + " — nessun null-check aggiunto, file invariato.");
            return false;
        }
        String guard = String.join(" && ", guards);
        String oldCode = codeEl.getTextContent().strip();

        if (oldCode.startsWith("if(" + guard + ")")) {
            System.out.println("[OK] wrap-nullcheck: " + node.getAttribute("name")
                    + " e' gia protetto da questa guardia — file invariato.");
            return false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("if(").append(guard).append(")\n{\n");
        for (String l : oldCode.split("\n", -1)) {
            sb.append(l.isBlank() ? "" : "    " + l).append("\n");
        }
        sb.append("}");

        replaceRange(startLine(codeEl), endLine(codeEl),
                codeBlock("code", indentOf(startLine(codeEl)), sb.toString()));
        validateAndReport("wrap-nullcheck", node.getAttribute("name")
                + " avvolto in " + guards.size() + " null-check (" + guard + ")");
        System.out.println("     suggerito: lanciare CarFormat per normalizzare l'indentazione.");
        return true;
    }

    /** Costruisce il blocco <tag><![CDATA[ ... ]]></tag> spezzando il codice multi-riga. */
    static List<String> codeBlock(String tag, String xmlIndent, String code) {
        String[] codeLines = code.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> block = new ArrayList<>();
        String open = xmlIndent + "<" + tag + "><![CDATA[";
        String close = "]]></" + tag + ">";
        if (codeLines.length == 1) {
            block.add(open + codeLines[0] + close);
        } else {
            block.add(open + codeLines[0]);
            for (int i = 1; i < codeLines.length - 1; i++) block.add(codeLines[i]);
            block.add(codeLines[codeLines.length - 1] + close);
        }
        return block;
    }

    void setLabel(String ref, String label) throws Exception {
        Element node = resolveNode(singleFlow(), ref);
        Element labelEl = firstChild(node, "label");
        if (labelEl == null) throw new IllegalStateException("nodo senza <label>");
        String indent = indentOf(startLine(labelEl));
        replaceRange(startLine(labelEl), endLine(labelEl),
                List.of(indent + "<label>" + escapeXml(label) + "</label>"));
        validateAndReport("set-label", "label di " + node.getAttribute("name") + " aggiornata");
    }

    // ---- edit chirurgici (numeri di riga 1-based, applicare dal basso) ---------

    void insertAfter(int line, List<String> newLines) {
        lines.addAll(line, newLines);
    }

    void replaceRange(int from, int to, List<String> newLines) {
        for (int i = to; i >= from; i--) lines.remove(i - 1);
        lines.addAll(from - 1, newLines);
    }

    void replaceInRange(int from, int to, String regex, String replacement) {
        Pattern p = Pattern.compile(regex);
        int hits = 0;
        for (int i = from - 1; i < to; i++) {
            Matcher m = p.matcher(lines.get(i));
            if (m.find()) {
                lines.set(i, m.replaceFirst(Matcher.quoteReplacement(replacement)));
                hits++;
            }
        }
        if (hits != 1) {
            throw new IllegalStateException("Atteso 1 match di [" + regex + "] nelle righe "
                    + from + "-" + to + ", trovati " + hits);
        }
    }

    // ---- validazione e salvataggio ---------------------------------------------

    /** Riparsa il testo modificato in memoria e verifica le invarianti del grafo. */
    void validateAndReport(String op, String summary) throws Exception {
        Document newDoc = parse(text());   // lancia se l'XML non è well-formed
        Set<String> defined = new HashSet<>();
        for (Element fe : tags(newDoc, "flowelement")) {
            if (!defined.add(fe.getAttribute("uid"))) {
                throw new IllegalStateException("uid duplicato dopo l'edit: " + fe.getAttribute("uid"));
            }
        }
        for (String portTag : new String[] { "outputport", "inputport" }) {
            for (Element port : tags(newDoc, portTag)) {
                if (!defined.contains(port.getAttribute("uid"))) {
                    throw new IllegalStateException("dopo l'edit, " + portTag
                            + " referenzia uno uid inesistente: " + port.getAttribute("uid"));
                }
            }
        }
        this.doc = newDoc;
        System.out.println("[OK] " + op + ": " + summary);
    }

    void save() throws Exception {
        String out = (hadBom ? "﻿" : "") + text();
        Files.writeString(path, out, StandardCharsets.UTF_8);
        System.out.println("[OK] salvato " + path + " — ora esegui CarLint per il check completo");
    }

    // ---- risoluzione e generazione identità --------------------------------------

    Element singleFlow() {
        List<Element> flows = tags(doc, "messageflow");
        if (flows.size() != 1) {
            throw new IllegalStateException("Attesi 1 messageflow, trovati " + flows.size()
                    + " (supporto multi-flow non ancora implementato)");
        }
        return flows.get(0);
    }

    Element resolveNode(Element flow, String ref) {
        List<Element> matches = new ArrayList<>();
        for (Element fe : children(flow, "flowelement")) {
            if (ref.equals(fe.getAttribute("name")) || fe.getAttribute("uid").startsWith(ref)) {
                matches.add(fe);
            }
        }
        if (matches.size() == 1) return matches.get(0);
        StringBuilder known = new StringBuilder();
        for (Element fe : children(flow, "flowelement")) {
            known.append(' ').append(fe.getAttribute("name"));
        }
        throw new IllegalStateException((matches.isEmpty() ? "Nodo non trovato: " : "Riferimento ambiguo: ")
                + ref + ". Nodi disponibili:" + known);
    }

    Set<String> allUids() {
        Set<String> uids = new HashSet<>();
        for (Element el : tags(doc, "*")) {
            for (String attr : new String[] { "id", "uid" }) {
                if (!el.getAttribute(attr).isEmpty()) uids.add(el.getAttribute(attr));
            }
        }
        return uids;
    }

    Set<String> allNames(Element flow) {
        Set<String> names = new HashSet<>();
        for (Element fe : children(flow, "flowelement")) names.add(fe.getAttribute("name"));
        return names;
    }

    static String freshUuid(Set<String> used) {
        String u;
        do { u = UUID.randomUUID().toString(); } while (!used.add(u));
        return u;
    }

    static String freshName(String type, Set<String> usedNames) {
        int max = 0;
        Pattern p = Pattern.compile(Pattern.quote(type) + "(\\d+)");
        for (String n : usedNames) {
            Matcher m = p.matcher(n);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return type + (max + 1);
    }

    String indentOf(int line) {
        String s = lines.get(line - 1);
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String shortUid(String uid) {
        int dash = uid.indexOf('-');
        return dash > 0 ? uid.substring(0, dash) + "…" : uid;
    }

    // ---- parsing DOM con righe di inizio e fine ------------------------------------

    static Document parse(String xml) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(false);
        SAXParser parser = spf.newSAXParser();

        DefaultHandler handler = new DefaultHandler() {
            final Deque<Element> stack = new ArrayDeque<>();
            final StringBuilder text = new StringBuilder();
            Locator locator;

            @Override public void setDocumentLocator(Locator l) { locator = l; }

            @Override public void startElement(String uri, String local, String qName, Attributes attrs) {
                flushText();
                Element el = doc.createElement(qName);
                for (int i = 0; i < attrs.getLength(); i++) {
                    el.setAttribute(attrs.getQName(i), attrs.getValue(i));
                }
                el.setUserData(START_LINE, locator == null ? -1 : locator.getLineNumber(), null);
                if (stack.isEmpty()) doc.appendChild(el); else stack.peek().appendChild(el);
                stack.push(el);
            }

            @Override public void endElement(String uri, String local, String qName) {
                flushText();
                Element el = stack.pop();
                el.setUserData(END_LINE, locator == null ? -1 : locator.getLineNumber(), null);
            }

            @Override public void characters(char[] ch, int start, int length) {
                text.append(ch, start, length);
            }

            void flushText() {
                if (text.length() > 0 && !stack.isEmpty()) {
                    stack.peek().appendChild(doc.createTextNode(text.toString()));
                }
                text.setLength(0);
            }
        };
        parser.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                     handler);
        return doc;
    }

    static int startLine(Element el) { return (Integer) el.getUserData(START_LINE); }
    static int endLine(Element el)   { return (Integer) el.getUserData(END_LINE); }

    static List<Element> tags(Document doc, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList all = doc.getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) result.add((Element) all.item(i));
        return result;
    }

    static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                result.add((Element) n);
            }
        }
        result.sort(Comparator.comparingInt(CarFile::startLine));
        return result;
    }

    static Element firstChild(Element parent, String tag) {
        List<Element> c = children(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    static String childText(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }
}

/**
 * Lettore di definizioni di messaggio da un cartridge-libreria, ridotto a ciò che
 * serve per i null-check. La logica di riferimento (mandatory/optional, comandi
 * messages/fields/nullcheck) vive in CarSchema.java; qui ne replichiamo solo il
 * minimo per tenere CarEdit eseguibile da solo (java CarEdit.java ...).
 */
class Schema {

    /** Espressioni isNotNull(...) per i segmenti opzionali lungo il path (vuoto se nessuno). */
    static List<String> guards(Path lib, String message, String dottedPath, String base)
            throws Exception {
        Document doc = loadTolerant(lib);
        Element msg = findMessage(doc, message);
        Element datafields = firstDescendant(msg, "datafields");
        if (datafields == null) {
            throw new IllegalStateException("Nessun <datafields> nel messaggio " + message);
        }

        Element container = datafields;
        String containerName = "(radice)";
        StringBuilder access = new StringBuilder(base);
        List<String> guards = new ArrayList<>();
        for (String seg : dottedPath.split("\\.")) {
            Element next = resolveChild(container, seg);
            if (next == null) {
                throw new IllegalStateException("Campo non trovato: \"" + seg + "\" sotto \""
                        + containerName + "\". Figli disponibili: " + childNames(container));
            }
            access.append(".").append(seg);
            if (!isMandatory(next)) guards.add("isNotNull(" + access + ")");
            container = firstChild(next, "fields");
            containerName = seg;
        }
        return guards;
    }

    /** Mandatory se <required>true</required> oppure <minoccurs> >= 1; altrimenti optional. */
    static boolean isMandatory(Element field) {
        String req = childText(field, "required");
        if (!req.isEmpty()) return req.equalsIgnoreCase("true");
        for (String tag : new String[] { "minoccurs", "minOccurs" }) {
            String mo = childText(field, tag);
            if (!mo.isEmpty()) {
                try { return Integer.parseInt(mo.trim()) >= 1; } catch (NumberFormatException ignore) {}
            }
        }
        return false;
    }

    static Element findMessage(Document doc, String name) {
        NodeList nl = doc.getElementsByTagName("externalmessage");
        StringBuilder known = new StringBuilder();
        for (int i = 0; i < nl.getLength(); i++) {
            Element m = (Element) nl.item(i);
            if (name.equals(m.getAttribute("rulesname"))) return m;
            known.append("\n  ").append(m.getAttribute("rulesname"));
        }
        throw new IllegalStateException("Messaggio non trovato: " + name + ". Disponibili:" + known);
    }

    static Element resolveChild(Element container, String name) {
        if (container == null) return null;
        for (Element f : childElements(container, "field")) {
            if (name.equals(childText(f, "name"))) return f;
        }
        return null;
    }

    static String childNames(Element container) {
        List<String> names = new ArrayList<>();
        if (container != null) {
            for (Element f : childElements(container, "field")) names.add(childText(f, "name"));
        }
        return names.toString();
    }

    static Document loadTolerant(Path path) throws Exception {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (raw.startsWith("﻿")) raw = raw.substring(1);
        Matcher decl = Pattern.compile("^\\s*<\\?xml.*?\\?>", Pattern.DOTALL).matcher(raw);
        String body = decl.find() ? raw.substring(decl.end()) : raw;
        String wrapped = "<carlint-root xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + body + "</carlint-root>";
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8))));
    }

    static Element firstDescendant(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    static List<Element> childElements(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    static Element firstChild(Element parent, String tag) {
        List<Element> c = childElements(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    static String childText(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }
}
