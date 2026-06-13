# vol-agent — toolkit per cartridge Volante Designer (.car)

Quattro strumenti Java a file singolo (Java 11+, solo libreria standard, nessuna
dipendenza). Si lanciano in modalità source-file senza compilare:

```
java CarLint.java   <file.car> [altri.car ...]
java CarEdit.java   <file.car> <list|insert-between|set-code|set-label|wrap-nullcheck> ...
java CarFormat.java <file.car> [--check] [--strip-blank-lines] [--out <file>]
java CarSchema.java <lib.car>  <messages|fields|nullcheck> ...
```

Tutti condividono lo stesso principio: il `.car` è un grafo serializzato in XML in
cui l'identità è data da `uid`/`id` opachi e l'integrità è referenziale (i `link`
puntano ai `flowelement` per uid, non per posizione). Gli strumenti trattano questi
invarianti come un contratto e li verificano, così che modifiche automatiche — di un
umano o di un agente — non possano corrompere il file in modo silenzioso.

## CarLint — validazione
Controlla un cartridge senza modificarlo. Exit code 0 = pulito, 1 = errori, 2 = XML
non analizzabile. Pensato come quality gate in CI e come harness di sicurezza per un
agente.

| Codice | Severità | Controllo |
|--------|----------|-----------|
| E001 | error | uid/id duplicato (solo dove l'uid definisce identità) |
| E002 | error | link verso uno uid di flowelement inesistente |
| E003 | error | nodo non raggiungibile da Start |
| E004–E007 | error | flow senza Start, name duplicati, Stop con uscite, Start con entrate |
| W001 | warn | uid non conforme al formato UUID 8-4-4-4-12 |
| W002 | warn | ramo che non termina in Stop |
| W003 | warn | reference con absolute-path (portabilità) |
| W004–W006 | warn | If senza entrambi i rami, inputport con portindex ≠ 0, più link dalla stessa porta |

## CarEdit — modifica strutturale
Operazioni a grana grossa: l'agente decide *cosa*, il tool genera gli UUID, ricabla i
link e preserva la formattazione con edit chirurgici a livello di riga. Ogni modifica
è validata in memoria (well-formed + uid unici + integrità referenziale) **prima** di
scrivere su disco; se fallisce, il file resta intatto.

- `list` — stampa nodi e link (discovery del grafo senza leggere l'XML)
- `insert-between <A> <B> [--type Custom] [--name N] [--label "..."] [--code-file f]`
- `set-code <nodo> (--code "..." | --code-file f)`
- `set-label <nodo> --label "..."`
- `wrap-nullcheck <nodo> --lib <lib.car> --message <rulesname> --path <a.b.c> [--base var]`

I nodi si indicano per name (`Custom3`) o per prefisso univoco di uid. Per il codice
usare sempre `--code-file` (il quoting inline da shell perde i caratteri speciali).

`wrap-nullcheck` è **opt-in**: avvolge il codice esistente di un nodo Custom in una
guardia `if(isNotNull(...) && ...)` per i soli segmenti opzionali del path, leggendo la
definizione del campo dal cartridge-libreria (stessa logica di CarSchema). Se tutti i
segmenti del path sono mandatory non modifica nulla; è idempotente (non raddoppia una
guardia già presente). Gli altri comandi non aggiungono mai null-check da soli — l'utente
sceglie esplicitamente quando proteggere un accesso. Dopo il wrap, lanciare CarFormat per
normalizzare l'indentazione.

## CarFormat — formattazione del codice TML
Normalizza **solo** il contenuto dei CDATA di `<code>` e `<condition>` (tab→4 spazi,
niente spazi finali, reindentazione sulle graffe ignorando stringhe e commenti `//`,
righe vuote collassate). Tutto il resto del file resta intatto al byte. Riparsa il
risultato per garantire che resti XML well-formed.

Per default le righe vuote dentro al codice vengono indentate al livello corrente,
**come fa Volante Designer**: questo rende il round-trip stabile (riformattare → aprire
nel Designer → salvare non produce diff spuri). `--strip-blank-lines` le rende invece
completamente vuote (stile lint classico). `--check` non scrive ed esce con 1 se il
file non è già formattato — utile in CI.

> La validazione "il file è ancora apribile in Volante Designer" va completata con la
> **build headless** del Designer: CarFormat garantisce il well-formed XML, la build
> garantisce la semantica del cartridge.

## CarSchema — definizioni dei campi e null-check
Legge un cartridge-libreria referenziato (es. `AUSPAYMX_camt.car`) ed estrae, per ogni
messaggio, l'albero dei campi con la marcatura mandatory/optional. È il **prerequisito**
per inserire null-check corretti nel mapping.

Regola: un campo è **mandatory** se ha `<required>true</required>` oppure
`<minoccurs> >= 1`; altrimenti è **optional**.

- `messages` — elenca i messaggi (rulesname) del cartridge
- `fields <message>` — albero dei campi con `[M]`/`[O]`, datatype e facet (len, enum)
- `nullcheck <message> <path.con.punti> [--base var]` — genera la guardia `isNotNull`
  per ogni segmento opzionale lungo il path, così l'accesso al campo è sicuro

Il parser è tollerante: gestisce anche gli estratti di cartridge privi del tag radice
`<cartridge>` avvolgendoli in una radice sintetica.

## Workflow per un agente autonomo

```
                 ┌─────────────────────────────────────────────┐
                 │  task: "aggiungi mapping del campo X"        │
                 └───────────────────┬─────────────────────────┘
                                     ▼
   CarSchema fields/nullcheck  ──►  scopre se X è optional e genera la guardia
                                     ▼
   CarEdit insert-between/set-code ─►  applica la modifica (uid + ricablaggio gestiti)
                                     ▼
   CarEdit wrap-nullcheck (opt-in) ─►  protegge l'accesso ai campi opzionali
                                     ▼
   CarFormat                    ──►  normalizza il codice toccato
                                     ▼
   CarLint                      ──►  gate strutturale (exit ≠ 0 = correggi e ripeti)
                                     ▼
   build headless Volante       ──►  conferma definitiva (il .car è apribile/compila)
```

L'agente non scrive mai XML libero: lavora solo attraverso questi comandi
deterministici, e ad ogni passo riceve un errore azionabile (con numero di riga o
elenco dei nomi disponibili) su cui auto-correggersi.
