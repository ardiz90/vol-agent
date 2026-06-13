import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

/**
 * CarFormat — formattatore del codice TML contenuto nei CDATA di un cartridge .car.
 *
 * Normalizza SOLO il contenuto degli elementi <code> e <condition> (lo pseudocodice
 * di Volante). Tutto il resto del file — descrizioni, XSD, struttura XML — resta
 * intatto al byte. Dopo aver riformattato, riparsa il risultato in memoria per
 * garantire che sia ancora XML well-formed PRIMA di scrivere su disco; la validazione
 * finale "apribile in Volante Designer" va fatta con la build headless del Designer.
 *
 * Regole di formattazione (conservative — solo spazi/tab/a-capo, niente restyling):
 *   - tab convertiti in 4 spazi
 *   - rimozione degli spazi a fine riga
 *   - reindentazione in base alla profondità delle graffe { } (le stringhe e i
 *     commenti // vengono ignorati nel conteggio)
 *   - righe vuote multiple collassate in una sola; niente righe vuote in testa/coda
 *
 * Uso:
 *   java CarFormat.java <file.car> [--check] [--out <file>]
 *     --check : non scrive, esce con codice 1 se il file NON è già formattato
 *     --out f : scrive il risultato su f invece che sul file di input
 */
public class CarFormat {

    static final String INDENT = "    ";          // 4 spazi
    static final String START_LINE = "fmt.start", END_LINE = "fmt.end";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Uso: java CarFormat.java <file.car> [--check] [--out <file>]");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        boolean check = false;
        boolean stripBlankLines = false;
        Path out = input;
        for (int i = 1; i < args.length; i++) {
            if ("--check".equals(args[i])) check = true;
            else if ("--strip-blank-lines".equals(args[i])) stripBlankLines = true;
            else if ("--out".equals(args[i]) && i + 1 < args.length) out = Path.of(args[++i]);
        }

        String raw = Files.readString(input, StandardCharsets.UTF_8);
        boolean hadBom = raw.startsWith("﻿");
        String body = hadBom ? raw.substring(1) : raw;
        String eol = body.contains("\r\n") ? "\r\n" : "\n";
        boolean endedWithEol = body.endsWith("\n");

        List<String> lines = new ArrayList<>(List.of(body.split("\r?\n", -1)));
        if (endedWithEol && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        Document doc = parse(String.join("\n", lines));

        // raccoglie gli elementi da formattare e li ordina dal basso verso l'alto
        List<Element> targets = new ArrayList<>();
        for (String tag : new String[] { "code", "condition" }) {
            NodeList nl = doc.getElementsByTagName(tag);
            for (int i = 0; i < nl.getLength(); i++) targets.add((Element) nl.item(i));
        }
        targets.sort(Comparator.comparingInt((Element e) -> (Integer) e.getUserData(START_LINE)).reversed());

        int reformatted = 0;
        for (Element el : targets) {
            int start = (Integer) el.getUserData(START_LINE);
            int end = (Integer) el.getUserData(END_LINE);
            String code = el.getTextContent();
            if (code.strip().isEmpty()) continue;                 // CDATA vuoto: lascia com'è

            String xmlIndent = leadingWhitespace(lines.get(start - 1));
            List<String> formatted = formatCode(code, stripBlankLines);
            List<String> block = wrap(el.getTagName(), xmlIndent, formatted);

            List<String> original = new ArrayList<>(lines.subList(start - 1, end));
            if (!original.equals(block)) {
                for (int i = end; i >= start; i--) lines.remove(i - 1);
                lines.addAll(start - 1, block);
                reformatted++;
            }
        }

        String result = (hadBom ? "﻿" : "") + String.join(eol, lines) + (endedWithEol ? eol : "");

        // validazione: il risultato deve restare well-formed
        parse(String.join("\n", lines));

        if (check) {
            if (reformatted > 0) {
                System.out.println("[CHECK] " + input + ": " + reformatted
                        + " blocco/i di codice NON formattati");
                System.exit(1);
            }
            System.out.println("[CHECK] " + input + ": gia formattato");
            return;
        }

        Files.writeString(out, result, StandardCharsets.UTF_8);
        System.out.println("[OK] " + out + ": " + reformatted
                + " blocco/i riformattati (XML well-formed verificato)");
        System.out.println("     ricorda di aprire il file in Volante Designer / lanciare la build "
                + "per la conferma definitiva.");
    }

    /** Avvolge il codice formattato nell'elemento <tag><![CDATA[ ... ]]></tag>. */
    static List<String> wrap(String tag, String xmlIndent, List<String> code) {
        List<String> block = new ArrayList<>();
        String open = xmlIndent + "<" + tag + "><![CDATA[";
        String close = "]]></" + tag + ">";
        if (code.size() == 1) {
            block.add(open + code.get(0) + close);
        } else {
            block.add(open + code.get(0));
            for (int i = 1; i < code.size() - 1; i++) block.add(code.get(i));
            block.add(code.get(code.size() - 1) + close);
        }
        return block;
    }

    /**
     * Reindenta il codice TML in base alla profondità delle graffe.
     * Le righe vuote vengono collassate in una sola; per default ricevono
     * l'indentazione del livello corrente (come fa Volante Designer, per
     * stabilità nei round-trip). Con stripBlankLines = true diventano vuote.
     */
    static List<String> formatCode(String code, boolean stripBlankLines) {
        String[] raw = code.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean blankPending = false;

        for (String line : raw) {
            String trimmed = expandTabs(line).strip();
            if (trimmed.isEmpty()) { blankPending = true; continue; }

            int leadingClose = countLeadingClosers(trimmed);
            int effDepth = Math.max(0, depth - leadingClose);

            if (!result.isEmpty() && blankPending) {                 // collassa le righe vuote
                result.add(stripBlankLines ? "" : INDENT.repeat(Math.max(0, depth)));
            }
            blankPending = false;

            result.add(INDENT.repeat(effDepth) + trimmed);

            depth += braceDelta(trimmed);
            if (depth < 0) depth = 0;
        }
        if (result.isEmpty()) result.add("");
        return result;
    }

    /** Conta le graffe di apertura meno quelle di chiusura, ignorando stringhe e commenti. */
    static int braceDelta(String s) {
        int delta = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; }
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') break;  // commento di riga
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    /** Numero di '}' consecutivi a inizio riga (per dedentare la riga corrente). */
    static int countLeadingClosers(String trimmed) {
        int n = 0;
        for (int i = 0; i < trimmed.length() && trimmed.charAt(i) == '}'; i++) n++;
        return n;
    }

    static String expandTabs(String s) { return s.replace("\t", INDENT); }

    static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    // ---- parsing DOM con righe di inizio e fine --------------------------------

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
}
