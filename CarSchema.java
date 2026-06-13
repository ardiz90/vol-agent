import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * CarSchema — estrae le definizioni dei messaggi da un cartridge-libreria Volante
 * (es. AUSPAYMX_camt.car) e genera i null-check per i campi opzionali.
 *
 * Un campo è MANDATORY se ha un figlio <required>true</required> oppure
 * <minoccurs> &gt;= 1; altrimenti è OPTIONAL. È l'informazione che serve per
 * decidere quando un mapping deve proteggersi con un isNotNull(...).
 *
 * Comandi:
 *   java CarSchema.java <lib.car> messages
 *       elenca i messaggi (rulesname) definiti nel cartridge
 *   java CarSchema.java <lib.car> fields <message>
 *       stampa l'albero dei campi del messaggio con marcatura [M]/[O]
 *   java CarSchema.java <lib.car> nullcheck <message> <path.con.punti> [--base var]
 *       genera l'espressione di guardia null per accedere a quel path in sicurezza
 *
 * Il <path> è relativo alla radice del messaggio (primo segmento = campo top-level,
 * es. ResolutionOfInvestigation.Assignment.Assigner). --base è il nome della
 * variabile nel codice (default "msg").
 */
public class CarSchema {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); }
        Path lib = Path.of(args[0]);
        String command = args[1];

        Document doc = loadTolerant(lib);
        List<Element> messages = childMessages(doc);

        switch (command) {
            case "messages":
                System.out.println("Messaggi definiti in " + lib + ":");
                for (Element m : messages) {
                    System.out.printf("  %-32s  %s%n",
                            m.getAttribute("rulesname"), childText(m, "standard-name"));
                }
                break;

            case "fields": {
                require(args.length >= 3, "fields richiede <message>");
                Element msg = findMessage(messages, args[2]);
                Element datafields = firstDescendant(msg, "datafields");
                System.out.println("Campi di " + args[2] + "  ([M]=mandatory, [O]=optional):");
                for (Element f : childFields(datafields)) printTree(f, 1);
                break;
            }

            case "nullcheck": {
                require(args.length >= 4, "nullcheck richiede <message> <path>");
                String base = optValue(args, "--base", "msg");
                Element msg = findMessage(messages, args[2]);
                generateNullCheck(msg, args[3], base);
                break;
            }

            default:
                usage();
                System.exit(2);
        }
    }

    static void usage() {
        System.err.println("Uso: java CarSchema.java <lib.car> <messages|fields|nullcheck> ...");
    }

    static void require(boolean cond, String msg) {
        if (!cond) { System.err.println("Errore: " + msg); System.exit(2); }
    }

    // ---- comandi ----------------------------------------------------------------

    static void printTree(Element field, int depth) {
        String name = childText(field, "name");
        String dt = childText(field, "datatype");
        String mark = isMandatory(field) ? "[M]" : "[O]";
        String extra = facetSummary(field);
        System.out.printf("%-40s %s %-8s%s%n",
                "  ".repeat(depth) + name, mark, dt, extra.isEmpty() ? "" : "  " + extra);
        Element fields = firstChild(field, "fields");
        if (fields != null) for (Element f : childFields(fields)) printTree(f, depth + 1);
    }

    static void generateNullCheck(Element msg, String dottedPath, String base) {
        Element datafields = firstDescendant(msg, "datafields");
        String[] segs = dottedPath.split("\\.");

        List<String> segNames = new ArrayList<>();
        List<Boolean> optional = new ArrayList<>();

        Element container = datafields;
        String containerName = "(radice)";
        for (String seg : segs) {
            Element next = resolveChild(container, seg);
            if (next == null) {
                System.err.println("Campo non trovato: \"" + seg + "\" sotto \"" + containerName
                        + "\". Figli disponibili: " + childNames(container));
                System.exit(2);
            }
            segNames.add(seg);
            optional.add(!isMandatory(next));
            container = firstChild(next, "fields");   // null se è una foglia
            containerName = seg;
        }

        System.out.println("Path: " + base + "." + dottedPath);
        StringBuilder access = new StringBuilder(base);
        List<String> guards = new ArrayList<>();
        for (int i = 0; i < segNames.size(); i++) {
            access.append(".").append(segNames.get(i));
            String mark = optional.get(i) ? "[O]" : "[M]";
            System.out.printf("  %s %s%n", mark, access);
            if (optional.get(i)) guards.add("isNotNull(" + access + ")");
        }

        System.out.println();
        if (guards.isEmpty()) {
            System.out.println("// tutti i segmenti sono mandatory: nessun null-check necessario");
            System.out.println("// (l'accesso diretto a " + base + "." + dottedPath + " e sicuro)");
        } else {
            String guard = String.join(" && ", guards);
            System.out.println("Guardia (tutti i segmenti opzionali devono esistere):");
            System.out.println("if(" + guard + ")");
            System.out.println("{");
            System.out.println("    // ... usa " + base + "." + dottedPath);
            System.out.println("}");
        }
    }

    // ---- semantica mandatory/optional ----------------------------------------------

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

    static String facetSummary(Element field) {
        Element facets = firstChild(field, "facets");
        if (facets == null) return "";
        List<String> parts = new ArrayList<>();
        String min = attrOfChild(facets, "minLength", "value");
        String max = attrOfChild(facets, "maxLength", "value");
        if (!min.isEmpty() || !max.isEmpty()) parts.add("len " + (min.isEmpty() ? "?" : min) + ".." + (max.isEmpty() ? "?" : max));
        List<String> enums = new ArrayList<>();
        for (Element e : childElements(facets, "enumeration")) enums.add(e.getAttribute("value"));
        if (!enums.isEmpty()) parts.add("enum{" + String.join(",", enums) + "}");
        return String.join(" ", parts);
    }

    // ---- navigazione del modello ---------------------------------------------------

    static List<Element> childMessages(Document doc) {
        List<Element> result = new ArrayList<>();
        NodeList nl = doc.getElementsByTagName("externalmessage");
        for (int i = 0; i < nl.getLength(); i++) result.add((Element) nl.item(i));
        return result;
    }

    static Element findMessage(List<Element> messages, String name) {
        for (Element m : messages) if (name.equals(m.getAttribute("rulesname"))) return m;
        StringBuilder known = new StringBuilder();
        for (Element m : messages) known.append("\n  ").append(m.getAttribute("rulesname"));
        System.err.println("Messaggio non trovato: " + name + ". Disponibili:" + known);
        System.exit(2);
        return null;
    }

    /** I <field> figli diretti del contenitore (datafields o <fields>). */
    static List<Element> childFields(Element container) {
        return container == null ? List.of() : childElements(container, "field");
    }

    /** Cerca tra i field figli quello con <name> uguale a name. */
    static Element resolveChild(Element container, String name) {
        if (container == null) return null;
        for (Element f : childFields(container)) {
            if (name.equals(childText(f, "name"))) return f;
        }
        return null;
    }

    static String childNames(Element container) {
        List<String> names = new ArrayList<>();
        for (Element f : childFields(container)) names.add(childText(f, "name"));
        return names.toString();
    }

    // ---- utilità DOM ----------------------------------------------------------------

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

    /** Primo discendente (a qualsiasi profondità) con il tag dato. */
    static Element firstDescendant(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    static String childText(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    static String attrOfChild(Element parent, String tag, String attr) {
        Element c = firstChild(parent, tag);
        return c == null ? "" : c.getAttribute(attr);
    }

    static String optValue(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) if (key.equals(args[i])) return args[i + 1];
        return def;
    }

    // ---- parsing tollerante (gestisce anche estratti senza radice <cartridge>) -------

    static Document loadTolerant(Path path) throws Exception {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (raw.startsWith("﻿")) raw = raw.substring(1);
        // rimuove la dichiarazione <?xml ... ?> per poter avvolgere in una radice sintetica
        Matcher decl = Pattern.compile("^\\s*<\\?xml.*?\\?>", Pattern.DOTALL).matcher(raw);
        String body = decl.find() ? raw.substring(decl.end()) : raw;
        String wrapped = "<carlint-root xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + body + "</carlint-root>";
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return db.parse(new InputSource(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8))));
    }
}
