import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

/**
 * CarLint — linter per cartridge Volante Designer (.car).
 *
 * Uso:  java CarLint.java file1.car [file2.car ...]
 * Exit code: 0 = nessun errore, 1 = almeno un ERROR, 2 = problema di I/O o XML non valido.
 *
 * Controlli:
 *   E001  uid/id duplicato nel file
 *   E002  link che referenzia uno uid di flowelement inesistente
 *   E003  flowelement non raggiungibile dal nodo Start
 *   E004  messageflow senza nodo Start
 *   E005  name di flowelement duplicato nello stesso messageflow
 *   E006  nodo Stop con link in uscita
 *   E007  nodo Start con link in entrata
 *   W001  uid/id che non rispetta il formato UUID standard (8-4-4-4-12 hex)
 *   W002  nodo senza link in uscita che non è uno Stop (vicolo cieco)
 *   W003  reference con absolute-path valorizzato (problema di portabilità)
 *   W004  nodo If senza entrambi i rami (port 1 = true, port 2 = false)
 *   W005  inputport con portindex diverso da 0
 *   W006  più link Default in uscita dalla stessa porta dello stesso nodo
 */
public class CarLint {

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    enum Severity { ERROR, WARN, INFO }

    static class Finding {
        final Severity severity;
        final String code;
        final int line;
        final String message;
        Finding(Severity severity, String code, int line, String message) {
            this.severity = severity; this.code = code; this.line = line; this.message = message;
        }
        @Override public String toString() {
            return String.format("[%-5s] %s  riga %-4d %s", severity, code, line, message);
        }
    }

    static class FlowElem {
        final String uid, name, type;
        final int line;
        FlowElem(String uid, String name, String type, int line) {
            this.uid = uid; this.name = name; this.type = type; this.line = line;
        }
        String label() { return name + " (" + type + ", uid " + shortUid(uid) + ")"; }
    }

    static class FlowLink {
        final String uid, type;
        final String outUid, inUid;
        final int outIdx, inIdx;
        final int line;
        FlowLink(String uid, String type, String outUid, int outIdx, String inUid, int inIdx, int line) {
            this.uid = uid; this.type = type;
            this.outUid = outUid; this.outIdx = outIdx;
            this.inUid = inUid; this.inIdx = inIdx;
            this.line = line;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Uso: java CarLint.java <file.car> [altri.car ...]");
            System.exit(2);
        }
        boolean anyError = false;
        for (String arg : args) {
            File f = new File(arg);
            System.out.println("== " + f.getPath() + " ==");
            List<Finding> findings;
            try {
                findings = lint(f);
            } catch (Exception e) {
                System.out.println("[ERROR] E000  XML non analizzabile: " + e.getMessage());
                anyError = true;
                continue;
            }
            findings.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line)
                                                     : a.severity.compareTo(b.severity));
            long errors = findings.stream().filter(x -> x.severity == Severity.ERROR).count();
            long warns  = findings.stream().filter(x -> x.severity == Severity.WARN).count();
            for (Finding x : findings) System.out.println(x);
            System.out.printf("-- %d error, %d warning%n%n", errors, warns);
            if (errors > 0) anyError = true;
        }
        System.exit(anyError ? 1 : 0);
    }

    static List<Finding> lint(File file) throws Exception {
        Document doc = parseWithLineNumbers(file);
        List<Finding> out = new ArrayList<>();

        checkUidsGlobal(doc, out);
        checkReferences(doc, out);

        NodeList flows = doc.getElementsByTagName("messageflow");
        for (int i = 0; i < flows.getLength(); i++) {
            checkMessageFlow((Element) flows.item(i), out);
        }
        return out;
    }

    // ---- controlli globali -------------------------------------------------

    /** Tag il cui attributo uid è un riferimento a un altro elemento, non una definizione. */
    private static final Set<String> UID_REF_TAGS = Set.of("outputport", "inputport");

    /** E001 duplicati, W001 formato UUID — sugli attributi id/uid che DEFINISCONO un'identità. */
    static void checkUidsGlobal(Document doc, List<Finding> out) {
        Map<String, Element> seen = new HashMap<>();
        for (Element el : allElements(doc)) {
            if (UID_REF_TAGS.contains(el.getTagName())) continue;
            for (String attr : new String[] { "id", "uid" }) {
                String v = el.getAttribute(attr);
                if (v.isEmpty()) continue;
                if (!UUID_RE.matcher(v).matches()) {
                    out.add(new Finding(Severity.WARN, "W001", line(el),
                            "<" + el.getTagName() + "> " + attr + "=\"" + v
                            + "\" non è un UUID standard (funziona, ma va normalizzato)"));
                }
                Element prev = seen.putIfAbsent(v, el);
                if (prev != null) {
                    out.add(new Finding(Severity.ERROR, "E001", line(el),
                            attr + " \"" + shortUid(v) + "\" duplicato: già usato da <"
                            + prev.getTagName() + "> alla riga " + line(prev)));
                }
            }
        }
    }

    /** W003 absolute-path nelle references. */
    static void checkReferences(Document doc, List<Finding> out) {
        NodeList refs = doc.getElementsByTagName("reference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            String abs = childText(ref, "absolute-path");
            String rel = childText(ref, "relative-path");
            if (!abs.isEmpty()) {
                out.add(new Finding(Severity.WARN, "W003", line(ref),
                        "reference \"" + (rel.isEmpty() ? abs : rel)
                        + "\" contiene un absolute-path legato alla macchina locale: " + abs));
            }
        }
    }

    // ---- controlli per messageflow ------------------------------------------

    static void checkMessageFlow(Element flow, List<Finding> out) {
        String flowName = flow.getAttribute("name");

        Map<String, FlowElem> elems = new LinkedHashMap<>();
        Map<String, FlowElem> byName = new HashMap<>();
        List<FlowLink> links = new ArrayList<>();

        for (Element el : directChildren(flow, "flowelement")) {
            FlowElem fe = new FlowElem(el.getAttribute("uid"), el.getAttribute("name"),
                                       el.getAttribute("type"), line(el));
            elems.put(fe.uid, fe);
            FlowElem prev = byName.putIfAbsent(fe.name, fe);
            if (prev != null) {
                out.add(new Finding(Severity.ERROR, "E005", fe.line,
                        "flowelement name \"" + fe.name + "\" duplicato nel flow " + flowName
                        + " (già alla riga " + prev.line + ")"));
            }
        }

        for (Element el : directChildren(flow, "link")) {
            Element op = firstChild(el, "outputport");
            Element ip = firstChild(el, "inputport");
            if (op == null || ip == null) {
                out.add(new Finding(Severity.ERROR, "E002", line(el),
                        "link " + shortUid(el.getAttribute("uid"))
                        + " senza outputport o inputport"));
                continue;
            }
            links.add(new FlowLink(el.getAttribute("uid"), el.getAttribute("type"),
                    op.getAttribute("uid"), parseIntSafe(op.getAttribute("portindex")),
                    ip.getAttribute("uid"), parseIntSafe(ip.getAttribute("portindex")),
                    line(el)));
        }

        // E002 integrità referenziale + adiacenza
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<FlowLink>> outgoingLinks = new HashMap<>();
        Set<String> hasIncoming = new HashSet<>();
        for (FlowLink l : links) {
            for (String[] side : new String[][] { { l.outUid, "outputport" }, { l.inUid, "inputport" } }) {
                if (!elems.containsKey(side[0])) {
                    out.add(new Finding(Severity.ERROR, "E002", l.line,
                            "link " + shortUid(l.uid) + ": " + side[1] + " referenzia uno uid inesistente \""
                            + side[0] + "\""));
                }
            }
            if (elems.containsKey(l.outUid) && elems.containsKey(l.inUid)) {
                outgoing.computeIfAbsent(l.outUid, k -> new ArrayList<>()).add(l.inUid);
                outgoingLinks.computeIfAbsent(l.outUid, k -> new ArrayList<>()).add(l);
                hasIncoming.add(l.inUid);
            }
            // W005 inputport con portindex inatteso
            if (l.inIdx != 0) {
                out.add(new Finding(Severity.WARN, "W005", l.line,
                        "link " + shortUid(l.uid) + ": inputport con portindex " + l.inIdx
                        + " (atteso 0)"));
            }
        }

        // E004 + E003 raggiungibilità da Start
        List<FlowElem> starts = new ArrayList<>();
        for (FlowElem fe : elems.values()) if ("Start".equals(fe.type)) starts.add(fe);
        if (starts.isEmpty()) {
            out.add(new Finding(Severity.ERROR, "E004", line(flow),
                    "messageflow " + flowName + " senza nodo Start"));
        } else {
            Set<String> reachable = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            for (FlowElem s : starts) { reachable.add(s.uid); queue.add(s.uid); }
            while (!queue.isEmpty()) {
                for (String next : outgoing.getOrDefault(queue.poll(), List.of())) {
                    if (reachable.add(next)) queue.add(next);
                }
            }
            for (FlowElem fe : elems.values()) {
                if (!reachable.contains(fe.uid)) {
                    out.add(new Finding(Severity.ERROR, "E003", fe.line,
                            fe.label() + " non raggiungibile da Start"));
                }
            }
        }

        // E006/E007/W002/W004/W006 — regole per tipo di nodo
        for (FlowElem fe : elems.values()) {
            List<FlowLink> outs = outgoingLinks.getOrDefault(fe.uid, List.of());
            boolean isStop = "Stop".equals(fe.type);
            boolean isStart = "Start".equals(fe.type);

            if (isStop && !outs.isEmpty()) {
                out.add(new Finding(Severity.ERROR, "E006", fe.line,
                        fe.label() + " è uno Stop ma ha " + outs.size() + " link in uscita"));
            }
            if (isStart && hasIncoming.contains(fe.uid)) {
                out.add(new Finding(Severity.ERROR, "E007", fe.line,
                        fe.label() + " è uno Start ma ha link in entrata"));
            }
            if (!isStop && outs.isEmpty()) {
                out.add(new Finding(Severity.WARN, "W002", fe.line,
                        fe.label() + " non ha link in uscita: ramo che non termina in uno Stop"));
            }
            if ("If".equals(fe.type)) {
                Set<Integer> ports = new HashSet<>();
                for (FlowLink l : outs) if ("Default".equals(l.type)) ports.add(l.outIdx);
                if (!ports.contains(1) || !ports.contains(2)) {
                    out.add(new Finding(Severity.WARN, "W004", fe.line,
                            fe.label() + ": rami collegati " + ports
                            + ", attesi entrambi (1 = true, 2 = false)"));
                }
            }
            // W006 stessa porta usata da più link Default
            Map<Integer, Integer> perPort = new HashMap<>();
            for (FlowLink l : outs) {
                if ("Default".equals(l.type)) perPort.merge(l.outIdx, 1, Integer::sum);
            }
            for (Map.Entry<Integer, Integer> e : perPort.entrySet()) {
                if (e.getValue() > 1) {
                    out.add(new Finding(Severity.WARN, "W006", fe.line,
                            fe.label() + ": " + e.getValue() + " link Default escono dalla stessa porta "
                            + e.getKey()));
                }
            }
        }
    }

    // ---- parsing DOM con numeri di riga --------------------------------------

    static final String LINE_KEY = "carlint.line";

    static Document parseWithLineNumbers(File file) throws Exception {
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
                el.setUserData(LINE_KEY, locator == null ? -1 : locator.getLineNumber(), null);
                if (stack.isEmpty()) doc.appendChild(el); else stack.peek().appendChild(el);
                stack.push(el);
            }

            @Override public void endElement(String uri, String local, String qName) {
                flushText();
                stack.pop();
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
        parser.parse(file, handler);
        return doc;
    }

    // ---- utilità ---------------------------------------------------------------

    static int line(Element el) {
        Object v = el.getUserData(LINE_KEY);
        return v instanceof Integer ? (Integer) v : -1;
    }

    static String shortUid(String uid) {
        int dash = uid.indexOf('-');
        return dash > 0 ? uid.substring(0, dash) + "…" : uid;
    }

    static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    static List<Element> allElements(Document doc) {
        List<Element> result = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) result.add((Element) all.item(i));
        return result;
    }

    static List<Element> directChildren(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    static Element firstChild(Element parent, String tag) {
        List<Element> c = directChildren(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    static String childText(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }
}
