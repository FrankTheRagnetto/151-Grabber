"""151 Grabber — la suite. Un solo comando: python3 autotest.py
Verde = «TUTTO OK» + exit 0. Casa ermetica, sito finto locale, nessun dato vero.
"""
import os, sys, tempfile, threading, io, time, hashlib
from pathlib import Path

VERA_CASA = Path.home()
RADICE = Path(tempfile.mkdtemp(prefix="grabber-test-", dir=VERA_CASA / ".cache"))
os.environ["HOME"] = str(RADICE)
os.environ["XDG_CONFIG_HOME"] = str(RADICE / "config")
os.environ["XDG_DATA_HOME"] = str(RADICE / "data")
os.environ["XDG_CACHE_HOME"] = str(RADICE / "cache")
os.environ["QT_QPA_PLATFORM"] = "offscreen"
for v in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(v, None)
os.environ["NO_PROXY"] = "127.0.0.1,localhost"
(RADICE / "Downloads").mkdir()

PROIBITE = [VERA_CASA / "Downloads", VERA_CASA / ".config" / "151-grabber"]

def _sigillo(evento, args):
    if evento == "open" and len(args) >= 2 and isinstance(args[0], (str, bytes, os.PathLike)):
        modo = args[1] or ""
        flag = args[2] if len(args) > 2 and isinstance(args[2], int) else 0
        if (isinstance(modo, str) and any(c in modo for c in "wax+")) or (flag & (os.O_WRONLY | os.O_RDWR)):
            p = Path(os.fsdecode(args[0])).resolve()
            for x in PROIBITE:
                if p == x or x in p.parents:
                    raise RuntimeError(f"SIGILLO: la prova scrive nei dati veri: {p}")
sys.addaudithook(_sigillo)

sys.path.insert(0, str(Path(__file__).resolve().parent))
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from PIL import Image
import grabber

OK = FAIL = 0
def check(etichetta, cond, dettaglio=""):
    global OK, FAIL
    if cond:
        OK += 1
    else:
        FAIL += 1
        print(f"FAIL [{etichetta}] {dettaglio}")

def png(w, h, colore):
    b = io.BytesIO(); Image.new("RGB", (w, h), colore).save(b, "PNG"); return b.getvalue()
def jpg(w, h, colore):
    b = io.BytesIO(); Image.new("RGB", (w, h), colore).save(b, "JPEG"); return b.getvalue()

# le immagini del sito finto: ogni foto ha un colore suo per riconoscerla
FILE = {
    "/a.jpg": jpg(300, 200, (255, 0, 0)),          # 1 img semplice
    "/b-small.jpg": jpg(100, 80, (0, 0, 50)),      # miniatura da srcset (scartata)
    "/b-big.jpg": jpg(800, 600, (0, 0, 255)),      # 2 srcset: la grande
    "/c-thumb.jpg": jpg(90, 90, (9, 9, 9)),        # miniatura sotto <a>
    "/c-full.png": png(640, 480, (0, 255, 0)),     # 3 la grande del link
    "/d.webp": None,                                # 4 picture/source (riempito sotto)
    "/e.jpg": jpg(200, 200, (255, 255, 0)),        # 5 lazy data-src
    "/f.png": png(400, 100, (255, 0, 255)),        # 6 sfondo inline
    "/g-copia.jpg": None,                           # stessa foto di a.jpg, altro link
    "/pixel.gif": b"GIF89a\x01\x00\x01\x00\x80\x00\x00\x00\x00\x00\xff\xff\xff!\xf9\x04\x01\x00\x00\x00\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00;",
    "/logo.svg": b"<svg xmlns='http://www.w3.org/2000/svg'/>",
    "/script.jpg": jpg(300, 300, (1, 2, 3)),       # dentro <script>: non va presa
    "/tronca.jpg": jpg(500, 500, (7, 7, 7)),       # arriva a metà
    "/File:scheda.jpg": b"<html>pagina che parla della foto</html>",  # link che non è foto
    "/i-mostrata.jpg": jpg(300, 300, (90, 40, 10)),  # 7 la riserva: la foto mostrata
    "/h.jpg": jpg(320, 240, (0, 255, 255)),        # 8 dopo tutto
}
b = io.BytesIO(); Image.new("RGB", (500, 300), (128, 0, 0)).save(b, "WEBP"); FILE["/d.webp"] = b.getvalue()
FILE["/g-copia.jpg"] = FILE["/a.jpg"]

PAGINA = """<!doctype html><html><head><title>Prova</title>
<script>var x = '<img src="/script.jpg">';</script></head><body>
<img src="a.jpg" alt="uno">
<img src="b-small.jpg" srcset="b-small.jpg 100w, b-big.jpg 800w">
<a href="/c-full.png"><img src="/c-thumb.jpg"></a>
<picture><source srcset="/d.webp" type="image/webp"><img src="/d.webp"></picture>
<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="/e.jpg">
<div style="background-image: url('/f.png')"></div>
<a href="/File:scheda.jpg"><img src="/i-mostrata.jpg"></a>
<img src="/a.jpg#dopo">
<img src="/g-copia.jpg">
<img src="/pixel.gif"><img src="/logo.svg"><img src="/manca.jpg"><img src="/tronca.jpg">
<noscript><img src="/script.jpg"></noscript>
<img src="/h.jpg">
</body></html>"""
ATTESI = ["/a.jpg", "/b-big.jpg", "/c-full.png", "/d.webp", "/e.jpg", "/f.png", "/i-mostrata.jpg", "/h.jpg"]

BANNER = png(728, 90, (10, 10, 10))
FILE["/banner.png"] = BANNER
for n, col in ((1, (200, 0, 0)), (2, (0, 200, 0)), (3, (0, 0, 200))):
    FILE[f"/cap{n}-a.jpg"] = jpg(400, 600, col)
    FILE[f"/cap{n}-b.jpg"] = jpg(400, 600, tuple(255 - c for c in col))
FILE["/about.jpg"] = jpg(300, 300, (50, 60, 70))
HTML = {
    "/indice": """<html><body><nav><a href="/">Home</a> <a href="/about">About</a> <a href="/login?next=/indice">Login</a></nav>
<a href="#top">top</a> <a href="mailto:x@y.z">mail</a> <a href="https://altro.example/manga/x/chapter-9">fuori</a>
<a href="/manga/x/cover.jpg">cover</a>
<ul><li><a href="/manga/x/chapter-1">Ch 1</a></li><li><a href="/manga/x/chapter-2">Ch 2</a></li>
<li><a href="/manga/x/chapter-2#commenti">Ch 2 again</a></li><li><a href="/manga/x/chapter-3/">Ch 3</a></li>
<li><a href="/manga/x/chapter-4">Ch 4 (vuoto)</a></li></ul>
<a href="/indice">questa</a></body></html>""",
    "/manga/x/chapter-1": '<img src="/banner.png"><img src="/cap1-a.jpg"><img src="/cap1-b.jpg"><a class="next" href="/manga/x/chapter-2">Next ›</a>',
    "/manga/x/chapter-2": '<img src="/banner.png"><img src="/cap2-a.jpg"><img src="/cap2-b.jpg">',
    "/manga/x/chapter-3/": '<img src="/banner.png"><img src="/cap3-a.jpg"><img src="/cap3-b.jpg">',
    "/manga/x/chapter-4": '<p>niente foto</p>',
    "/about": '<img src="/about.jpg">',
    "/login": '<form></form>',
    "/": '<p>home</p>',
}

INCIAMPI = {}   # percorso -> quante volte rispondere male prima di rispondere bene
CHIESTE = {}    # percorso -> quante volte il sito finto è stato chiamato
LENTE = {}      # percorso -> secondi di attesa prima di rispondere
TROPPO = set()  # pagine che, quando inciampano, dicono 429 «troppo in fretta» invece di 503
APERTE = {"ora": 0, "max": 0}   # pagine lente servite nello stesso momento
_lock_aperte = threading.Lock()

# [K] sito con le foto su un altro host: le pagine del capitolo stanno su un altro host che vuole come
# Referer solo la radice del sito (come manda Chrome), e in coda ci sono le miniature «consigliati»
REFERER_RIFIUTATI = []
for n in range(6):
    FILE[f"/kk/45/{n}.jpg"] = jpg(400, 600, (40 * n, 100, 255 - 40 * n))
SLUG = ["the-red-club", "a-long-day-by-the-seas", "paper-boats-at-noon", "write-a-name-card",
        "the-sleepy-magicians", "early-raindrops", "the-runaway-baker"]
for sl in SLUG:
    FILE[f"/thumb/{sl}.jpg"] = jpg(280, 400, (len(sl) * 7 % 255, 30, 60))
FILE["/kklogo.png"] = png(300, 80, (3, 140, 3))

# [ST] pagina + striscia di fondo certi siti: la striscia vera si cuce, il resto no
# rosso che scende piano, verde a righe verticali: sul taglio c'è disegno, come in un fumetto
_grad = Image.merge("RGB", (Image.linear_gradient("L").resize((400, 650)),
                            Image.linear_gradient("L").rotate(90).resize((40, 650)).resize((400, 650), Image.NEAREST),
                            Image.new("L", (400, 650), 128)))
_falsa = Image.new("RGB", (400, 50))
_falsa.putdata([((255, 255, 255) if (k % 400) % 2 else (0, 0, 0)) for k in range(400 * 50)])
def _png(im):
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()
STRISCE = {
    "/st/uno.png": _png(_grad.crop((0, 0, 400, 600))),
    "/st/uno-fondo.png": _png(_grad.crop((0, 600, 400, 650))),     # continua «uno»: si cuce
    "/st/doppia.png": png(800, 600, (90, 10, 10)),                 # doppia pagina: resta intera
    "/st/due.png": _png(_grad.crop((0, 0, 400, 600)).rotate(180)),
    "/st/tre.png": _png(_grad.crop((0, 10, 400, 610))),
    "/st/tre-finta.png": _png(_falsa),                              # stessa misura di una striscia, non continua
    "/st/bianca.png": png(400, 600, (255, 255, 255)),
    "/st/bianca-fondo.png": png(400, 50, (255, 255, 255)),          # bianco contro bianco: non prova niente, non si cuce
    "/st/quattro.png": _png(_grad.crop((0, 50, 400, 650))),
    "/st/cinque.png": _png(_grad.crop((0, 50, 400, 650)).transpose(Image.FLIP_TOP_BOTTOM)),   # pagina intera che «continua» quattro: non è una striscia
}
FILE.update(STRISCE)

class Sito(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        p = self.path if self.path in HTML else self.path.split("?")[0]
        CHIESTE[p] = CHIESTE.get(p, 0) + 1
        if p in LENTE:
            with _lock_aperte:
                APERTE["ora"] += 1; APERTE["max"] = max(APERTE["max"], APERTE["ora"])
            time.sleep(LENTE[p])
            with _lock_aperte:
                APERTE["ora"] -= 1
        if p.startswith("/kk/") and self.headers.get("Referer") != SITO + "/":
            REFERER_RIFIUTATI.append(self.headers.get("Referer"))
            self.send_response(403); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"no")
            return
        if INCIAMPI.get(p, 0) > 0:
            INCIAMPI[p] -= 1
            codice = 429 if p.endswith(".jpg") or p in TROPPO else 503
            self.send_response(codice); self.send_header("Retry-After", "0")
            self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"no")
            return
        if p in ("/", "/pagina"):
            corpo = PAGINA.encode(); self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
        elif p in HTML:
            corpo = HTML[p].encode(); self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
        elif p == "/vuota":
            corpo = b"<html><body><p>niente</p></body></html>"; self.send_response(200)
            self.send_header("Content-Type", "text/html")
        elif p in FILE:
            corpo = FILE[p]; self.send_response(200)
            self.send_header("Content-Type", "image/svg+xml" if p.endswith(".svg") else "application/octet-stream")
            if p == "/tronca.jpg":
                self.send_header("Content-Length", str(len(corpo)))
                self.end_headers(); self.wfile.write(corpo[: len(corpo) // 2]); self.wfile.flush()
                self.close_connection = True
                return
        else:
            corpo = b"no"; self.send_response(404)
        self.send_header("Content-Length", str(len(corpo))); self.end_headers(); self.wfile.write(corpo)

srv = ThreadingHTTPServer(("127.0.0.1", 0), Sito)
threading.Thread(target=srv.serve_forever, daemon=True).start()
SITO = f"http://127.0.0.1:{srv.server_address[1]}"
ALTRO = f"http://localhost:{srv.server_address[1]}"   # stesso server, altra origine (= altro host per il browser)
HTML["/manga/racconto/chapter-45"] = (
    f'<html><body><header><img src="/kklogo.png"></header>'
    f'<div class="container-chapter-reader">'
    + "".join(f"<img src='{ALTRO}/kk/45/{n}.jpg' alt='page {n + 1}' loading='lazy'>" for n in range(6))
    + '</div><div class="consigliati"><h2>Recommended</h2>'
    + "".join(f'<div class="item"><a href="/manga/{sl}"><img src="{ALTRO}/thumb/{sl}.jpg"></a><h3>{sl}</h3></div>' for sl in SLUG)
    + "</div></body></html>")
DL = RADICE / "Downloads"

def impronta(d): return hashlib.sha256(d).hexdigest()

# ---- [G1] ordine, la grande, niente doppioni/icone/svg/script
ris = grabber.scarica_tutto(SITO + "/pagina", DL, "Gita a Roma")
cart = DL / "Gita a Roma"
nomi = sorted(x.name for x in cart.iterdir())
check("G1-quante", ris.salvate == 8 and len(nomi) == 8, f"{ris.salvate} {nomi}")
# manca.jpg (404) e tronca.jpg non arrivano: i loro numeri (008, 009) restano liberi
check("G1-nomi", nomi == ["001.jpg", "002.jpg", "003.png", "004.webp", "005.jpg", "006.png", "007.jpg", "010.jpg"], str(nomi))
check("G1-mancanti", [m.stem for m in ris.mancanti] == ["008", "009"], str([m.stem for m in ris.mancanti]))
# manca.jpg: il sito dice 404 = link morto; tronca.jpg: arrivata a metà = inciampo, si riprova
check("G1-morta", [m.morta for m in ris.mancanti] == [True, False], str([(m.stem, m.morta) for m in ris.mancanti]))
for i, (n, att) in enumerate(zip(nomi, ATTESI), 1):
    check(f"G1-ordine-{i}", impronta((cart / n).read_bytes()) == impronta(FILE[att]), f"{n} non è {att}")
check("G1-doppie", ris.doppie >= 1, str(ris.doppie))
check("G1-piccole", ris.saltate_piccole == 1, str(ris.saltate_piccole))
check("G1-scheda-non-fallita", not any("scheda" in u for u, _ in ris.fallite), str(ris.fallite))
check("G1-fallite", any("manca.jpg" in u for u, _ in ris.fallite) and any("tronca.jpg" in u for u, _ in ris.fallite), str(ris.fallite))
check("G1-niente-part", not list(cart.glob("*.part")) and not list(cart.glob(".*")))

# ---- [G2] cartella già piena: errore, niente toccato
prima = {x.name: x.stat().st_mtime_ns for x in cart.iterdir()}
try:
    grabber.scarica_tutto(SITO + "/pagina", DL, "Gita a Roma"); check("G2-rifiuta", False, "nessun errore")
except grabber.GrabError:
    check("G2-rifiuta", True)
check("G2-intatta", prima == {x.name: x.stat().st_mtime_ns for x in cart.iterdir()})

# ---- [G3] nomi di cartella
check("G3-slash", grabber.nome_cartella_pulito("a/b\\c\n") == "a b c", grabber.nome_cartella_pulito("a/b\\c\n"))
check("G3-punti", grabber.nome_cartella_pulito("..") == "")
for brutto in ("", "   ", ".."):
    try:
        grabber.cartella_di_arrivo(DL, brutto); check(f"G3-vuoto-{brutto!r}", False)
    except grabber.GrabError:
        check(f"G3-vuoto-{brutto!r}", True)

# ---- [G4] pagina senza immagini: errore, nessuna cartella
try:
    grabber.scarica_tutto(SITO + "/vuota", DL, "Vuota"); check("G4", False)
except grabber.GrabError:
    check("G4", True)
check("G4-niente-cartella", not (DL / "Vuota").exists())

# ---- [G5] annulla: niente salvato
stop = threading.Event(); stop.set()
r5 = grabber.scarica_tutto(SITO + "/pagina", DL, "Annullata", stop=stop)
check("G5", r5.annullato and not (DL / "Annullata").exists())

# ---- [G6] srcset con x
check("G6", grabber._migliore_da_srcset("a.jpg 1x, b.jpg 2x") == "b.jpg")
check("G6-virgole-url", grabber._migliore_da_srcset("https://x/i.jpg?w=1,2 100w, https://x/j.jpg 900w") == "https://x/j.jpg")

# ---- [K] foto su un altro host (Referer = radice del sito) e solo il lettore del capitolo
_rp = getattr(grabber, "_referer_per", lambda *a: "assente")
check("K0-referer-stesso", _rp("https://a.it/m/c-1", "https://a.it/i/1.jpg") == "https://a.it/m/c-1")
check("K0-referer-altro", _rp("https://www.a.it/m/c-1?p=2", "https://cdn.b.com/1.jpg") == "https://www.a.it/")
check("K0-referer-porta", _rp("http://a.it:81/m", "http://a.it:82/1.jpg") == "http://a.it:81/")
check("K0-referer-mai-verso-http", _rp("https://a.it/m", "http://cdn.b.com/1.jpg") is None)
rk = grabber.scarica_tutto(SITO + "/manga/racconto/chapter-45", DL, "Racconto 45")
ck = _cartella = DL / "Racconto 45"
nk = sorted(x.name for x in ck.iterdir()) if ck.is_dir() else []
check("K1-referer", not REFERER_RIFIUTATI and not rk.fallite, f"rifiutati {REFERER_RIFIUTATI[:2]} fallite {rk.fallite[:2]}")
check("K2-solo-le-pagine", nk == [f"{i:03d}.jpg" for i in range(1, 7)], str(nk))
check("K3-ordine", [impronta((ck / x).read_bytes()) for x in nk] == [impronta(FILE[f"/kk/45/{n}.jpg"]) for n in range(6)])
check("K4-niente-miniature", not any(c.get(f"/thumb/{sl}.jpg") for sl in SLUG for c in [CHIESTE]),
      str([sl for sl in SLUG if CHIESTE.get(f"/thumb/{sl}.jpg")]))

# ---- [ST] strisce di fondo
HTML["/strisce"] = "".join(f'<img src="{k}">' for k in STRISCE)
rs = grabber.scarica_tutto(SITO + "/strisce", DL, "Strisce")
cs = DL / "Strisce"
ns = sorted(x.name for x in cs.iterdir()) if cs.is_dir() else []
check("ST1-quante", len(ns) == 9 and rs.strisce == 1, f"{ns} strisce {rs.strisce}")
if ns:
    with Image.open(cs / ns[0]) as prima:
        check("ST2-cucita-intera", prima.size == (400, 650) and list(prima.convert("RGB").getdata()) == list(_grad.getdata()),
              str(prima.size))
    with Image.open(cs / ns[1]) as d:
        check("ST3-doppia-intera", d.size == (800, 600), str(d.size))
    misure = [Image.open(cs / n).size for n in ns]
    check("ST4-finta-resta-sola", misure[4] == (400, 50) and misure[3] == (400, 600), str(misure))
    check("ST5-due-pagine-non-cucite", misure[7] == (400, 600) and misure[8] == (400, 600), str(misure))
    check("ST6-bianco-non-prova", misure[5] == (400, 600) and misure[6] == (400, 50), str(misure))

# ---- [M] più pagine: trovare, indovinare, scaricare
pag = grabber.cerca_pagine(SITO + "/indice")
nomi_p = [p.nome for p in pag]
check("M1-elenco", nomi_p == ["home", "about", "login?next=/indice", "chapter-1", "chapter-2", "chapter-3", "chapter-4"], str(nomi_p))
check("M1-niente-fuori-foto-se-stessa", not any("altro.example" in p.url or p.url.endswith(".jpg") or p.url == SITO + "/indice" for p in pag))
check("M2-indovina", [p.nome for p in pag if p.scelta] == ["chapter-1", "chapter-2", "chapter-3", "chapter-4"], str([p.nome for p in pag if p.scelta]))
for p in pag:
    if p.nome == "chapter-2":
        p.scelta = False   # chi lo usa ne toglie uno
r = grabber.scarica_pagine(pag, DL, "Manga X")
radice = DL / "Manga X"
sotto = sorted(x.name for x in radice.iterdir())
check("M3-cartelle", sotto == ["01 chapter-1", "02 chapter-3"], str(sotto))
def _elenca(d): return sorted(x.name for x in d.iterdir()) if d.is_dir() else []
c1 = _elenca(radice / "01 chapter-1")
c3 = _elenca(radice / "02 chapter-3")
check("M3-banner-fuori", c1 == ["001.jpg", "002.jpg"] and c3 == ["001.jpg", "002.jpg"], f"{c1} {c3}")
check("M3-arredo-contato", r.arredo == 1, str(r.arredo))
_f = radice / "02 chapter-3" / "001.jpg"
check("M3-ordine", _f.exists() and impronta(_f.read_bytes()) == impronta(FILE["/cap3-a.jpg"]))
check("M3-tolta-non-scaricata", not any("chapter-2" in x for x in sotto))
check("M3-vuota-detta", r.pagine_vuote == ["chapter-4"] and r.pagine_fatte == 2, f"{r.pagine_vuote} {r.pagine_fatte}")
try:
    grabber.scarica_pagine(pag, DL, "Manga X"); check("M4-rifiuta-piena", False)
except grabber.GrabError:
    check("M4-rifiuta-piena", True)
nessuna = [grabber.Pagina(p.url, p.nome, False) for p in pag]
try:
    grabber.scarica_pagine(nessuna, DL, "Niente"); check("M5-nessuna", False)
except grabber.GrabError:
    check("M5-nessuna", not (DL / "Niente").exists())
# [R] il sito inciampa (503 sulla pagina, 429 sulla foto): si riprova da soli
grabber.ATTESE = (0.05, 0.05, 0.05)
for p in pag:
    p.scelta = p.nome in ("chapter-1", "chapter-2")
INCIAMPI.update({"/manga/x/chapter-1": 2, "/cap2-a.jpg": 1})
r = grabber.scarica_pagine(pag, DL, "Inciampi")
check("R1-riprova", r.pagine_fatte == 2 and not r.pagine_fallite and not r.fallite and r.salvate == 4,
      f"{r.pagine_fatte} {r.pagine_fallite} {r.fallite} {r.salvate}")
INCIAMPI.update({"/manga/x/chapter-1": 9})
r = grabber.scarica_pagine(pag, DL, "Inciampi sempre")
check("R2-arrende-e-continua", r.pagine_fallite == ["chapter-1"] and r.pagine_fatte == 1, f"{r.pagine_fallite} {r.pagine_fatte}")
INCIAMPI.clear()
# [S] fermato a metà: le pagine già fatte restano salvate
for p in pag:
    p.scelta = p.nome.startswith("chapter")
ferma = threading.Event()
def av(f, t, m):
    if m.startswith("Page 2 of"):
        ferma.set()
totali = set()
def av(f, t, m):
    totali.add(t)
    if m.startswith("Page 2 of"):
        ferma.set()
r = grabber.scarica_pagine(pag, DL, "Fermata", av, ferma, insieme=1)
check("S2-barra-conta-pagine", totali == {4}, str(totali))
check("S1-resta-il-fatto", r.annullato and sorted(x.name for x in (DL / "Fermata").iterdir()) == ["01 chapter-1"],
      str(sorted(x.name for x in (DL / "Fermata").iterdir())) if (DL / "Fermata").exists() else "manca")
# [P] più capitoli nello stesso momento: il sito li vede insieme, le cartelle restano nell'ordine dell'elenco
for k in range(1, 7):
    HTML[f"/serie/chapter-{k}"] = f'<img src="/serie{k}.jpg">'
    FILE[f"/serie{k}.jpg"] = jpg(200 + k, 150, (k * 30, 10, 200))
    LENTE[f"/serie{k}.jpg"] = 0.3   # lente le foto, non le pagine: le prime 3 pagine si leggono insieme comunque
HTML["/serie/elenco"] = "".join(f'<a href="/serie/chapter-{k}">{k}</a>' for k in range(1, 7))
pag_s = grabber.cerca_pagine(SITO + "/serie/elenco")
APERTE["max"] = 0
t0 = time.time()
r = grabber.scarica_pagine(pag_s, DL, "Serie insieme")
dt = time.time() - t0
sotto = sorted(x.name for x in (DL / "Serie insieme").iterdir())
check("P1-insieme", APERTE["max"] == min(6, grabber.INSIEME), f"max {APERTE['max']} ({dt:.2f}s)")
check("P1-ordine", sotto == [f"0{k} chapter-{k}" for k in range(1, 7)], str(sotto))
check("P1-foto-giuste", all(impronta((DL / "Serie insieme" / f"0{k} chapter-{k}" / "001.jpg").read_bytes()) == impronta(FILE[f"/serie{k}.jpg"]) for k in range(1, 7)))
check("P1-conti", r.salvate == 6 and r.pagine_fatte == 6, f"{r.salvate} {r.pagine_fatte}")
# fermato a metà con più capitoli insieme: quello finito resta, quelli a metà non lasciano niente di rotto
ferma = threading.Event()
# insieme=3: con 6 capitoli e 8 insieme (0.7.0) partirebbero tutti subito e nessuno resterebbe fuori
r = grabber.scarica_pagine(pag_s, DL, "Serie fermata", lambda f, t, m: ferma.set() if f >= 1 else None, ferma, insieme=3)
fatte_s = sorted(x.name for x in (DL / "Serie fermata").iterdir())
check("S3-insieme-fermata", r.annullato and 1 <= len(fatte_s) < 6 and not any((DL / "Serie fermata").rglob("*.part")), str(fatte_s))
# [P2] il sito dice «troppo in fretta» (429) sulle prime 3 pagine: da lì vanno 8 - 3 = 5 capitoli insieme
for k in range(1, 13):
    HTML[f"/fretta/chapter-{k}"] = f'<img src="/fretta{k}.jpg">'
    FILE[f"/fretta{k}.jpg"] = jpg(210 + k, 170, (k * 20, 200, 30))
    LENTE[f"/fretta{k}.jpg"] = 0.3
HTML["/fretta/elenco"] = "".join(f'<a href="/fretta/chapter-{k}">{k}</a>' for k in range(1, 13))
pag_f = grabber.cerca_pagine(SITO + "/fretta/elenco")
for k in (1, 2, 3):
    TROPPO.add(f"/fretta/chapter-{k}"); INCIAMPI[f"/fretta/chapter-{k}"] = 1
APERTE["max"] = 0
r = grabber.scarica_pagine(pag_f, DL, "Fretta", insieme=8)
check("P2-freno", APERTE["max"] == 5 and r.rallentato and r.pagine_fatte == 12, f"max {APERTE['max']} {r.rallentato} {r.pagine_fatte}")
APERTE["max"] = 0
r = grabber.scarica_pagine(pag_f, DL, "Senza fretta", insieme=8)
check("P2-senza-freno-otto", APERTE["max"] == 8 and not r.rallentato, f"max {APERTE['max']} {r.rallentato}")
INCIAMPI.clear(); TROPPO.clear()
LENTE.clear()
# indovina senza gruppo chiaro: nessuna spunta, non inventa capitoli
sparse = grabber.indovina([grabber.Pagina(f"{SITO}/{x}", x) for x in ("a", "b/c", "d/e/f")])
check("M6-senza-gruppo-nessuna", not any(p.scelta for p in sparse))

# ---- [I] indovina sulle STRUTTURE dei siti veri: un sito finto per struttura,
# gli stessi file li legge GrabberTest.kt (android/app/src/test/resources/indovina/)
import re as _re
CASI_I = sorted((Path(__file__).resolve().parent / "android/app/src/test/resources/indovina").glob("*.html"))
check("I0-casi", len(CASI_I) >= 15, str(len(CASI_I)))
for f in CASI_I:
    t = f.read_text()
    pag_i = _re.search(r"<!-- pagina: (\S+) -->", t)[1]
    att_i = _re.search(r"<!-- attesi: (.*?) -->", t)[1]
    quanti_i = int(_re.search(r"<!-- quanti: (\d+) -->", t)[1])
    pp_i = grabber.indovina(grabber.trova_pagine(t, pag_i), pag_i)
    sp_i = [p.url for p in pp_i if p.scelta]
    giusti_i = [p.url for p in pp_i if _re.search(att_i, p.url)]
    check(f"I-{f.stem}", sp_i == giusti_i and len(sp_i) == quanti_i,
          f"{len(sp_i)} spuntati, attesi {quanti_i}: {[u.split('sito.com')[-1] for u in sp_i if u not in giusti_i][:3]} "
          f"mancati {[u.split('sito.com')[-1] for u in giusti_i if u not in sp_i][:3]}")
# dal sito finto vero, come fa la finestra: la serie letta dal server, i capitoli con l'id nella cartella
HTML["/manga/7/serie"] = (CASI_I[0].parent / "id-nella-cartella.html").read_text()
pag_i = grabber.cerca_pagine(SITO + "/manga/7/serie")
sp_i = [p.nome for p in pag_i if p.scelta]
check("I1-dal-server", len(sp_i) == 31 and sp_i[0] == "serie-chapter-30" and sp_i[-1] == "serie-chapter-10.5", f"{len(sp_i)} {sp_i[:2]}")
# nomi uguali in cartelle diverse si allungano
doppie = grabber.trova_pagine('<a href="/a/cap-1">x</a><a href="/b/cap-1">y</a>', SITO + "/z")
check("M7-nomi-distinti", [p.nome for p in doppie] == ["a/cap-1", "b/cap-1"], str([p.nome for p in doppie]))

# ---- [B] bottone «Try the missing ones again»: ogni foto al suo posto
FILE["/torna.jpg"] = jpg(333, 222, (12, 34, 56))
HTML["/buchi"] = '<img src="/a.jpg"><img src="/torna.jpg"><img src="/h.jpg">'
INCIAMPI["/torna.jpg"] = 99
r = grabber.scarica_tutto(SITO + "/buchi", DL, "Buchi")
check("B1-buco", sorted(x.name for x in (DL / "Buchi").iterdir()) == ["001.jpg", "003.jpg"] and [m.stem for m in r.mancanti] == ["002"],
      str(sorted(x.name for x in (DL / "Buchi").iterdir())))
check("B1-inciampo-non-morta", [m.morta for m in r.mancanti] == [False], str([(m.stem, m.morta) for m in r.mancanti]))
INCIAMPI.clear()
arr = grabber.riprova_mancanti(r)
f2 = DL / "Buchi" / "002.jpg"
check("B2-al-suo-posto", arr == 1 and f2.exists() and impronta(f2.read_bytes()) == impronta(FILE["/torna.jpg"]) and not r.mancanti and not r.fallite,
      f"{arr} {f2.exists()} {r.mancanti} {r.fallite}")
# [BM] foto che il sito ha tolto (404): link morto, numero libero, il bottone non la richiede
HTML["/morta"] = '<img src="/a.jpg"><img src="/sparita.jpg"><img src="/h.jpg">'
r = grabber.scarica_tutto(SITO + "/morta", DL, "Morta")
check("BM1-morta", sorted(x.name for x in (DL / "Morta").iterdir()) == ["001.jpg", "003.jpg"]
      and [(m.stem, m.morta) for m in r.mancanti] == [("002", True)], str([(m.stem, m.morta) for m in r.mancanti]))
prima_chieste = CHIESTE.get("/sparita.jpg", 0)
arr = grabber.riprova_mancanti(r)
check("BM2-non-richiesta", arr == 0 and CHIESTE.get("/sparita.jpg", 0) == prima_chieste
      and [(m.stem, m.morta) for m in r.mancanti] == [("002", True)] and any("sparita.jpg" in u for u, _ in r.fallite),
      f"{arr} {CHIESTE.get('/sparita.jpg')} vs {prima_chieste} {r.mancanti} {r.fallite}")
# foto che inciampava e al bottone risponde 404: da lì in poi è morta
FILE["/poi-sparita.jpg"] = jpg(321, 123, (90, 80, 70))
HTML["/poi-morta"] = '<img src="/a.jpg"><img src="/poi-sparita.jpg">'
INCIAMPI["/poi-sparita.jpg"] = 99
r = grabber.scarica_tutto(SITO + "/poi-morta", DL, "Poi morta")
INCIAMPI.clear(); del FILE["/poi-sparita.jpg"]
grabber.riprova_mancanti(r)
check("BM3-morta-al-bottone", [(m.stem, m.morta) for m in r.mancanti] == [("002", True)], str([(m.stem, m.morta) for m in r.mancanti]))
# pagina intera che non si apre: tiene il suo numero, il bottone la crea al suo posto
for p in pag:
    p.scelta = p.nome in ("chapter-1", "chapter-2", "chapter-3")
INCIAMPI["/manga/x/chapter-2"] = 99
r = grabber.scarica_pagine(pag, DL, "Pagine buche")
check("B3-pagina-buca", sorted(x.name for x in (DL / "Pagine buche").iterdir()) == ["01 chapter-1", "03 chapter-3"] and r.pagine_fallite == ["chapter-2"],
      str(sorted(x.name for x in (DL / "Pagine buche").iterdir())))
INCIAMPI.clear()
grabber.riprova_mancanti(r)
c2 = DL / "Pagine buche" / "02 chapter-2"
check("B4-pagina-tornata", c2.is_dir() and [impronta(x.read_bytes()) for x in sorted(c2.iterdir())] == [impronta(FILE["/cap2-a.jpg"]), impronta(FILE["/cap2-b.jpg"])] and not r.pagine_fallite and not r.mancanti,
      str(sorted(x.name for x in c2.iterdir())) if c2.is_dir() else "manca")

# ---- [T] stessa foto in due misure: resta la grande
check("T1-gemelle", grabber.gemella("https://x/c/simulation_2x.png") == grabber.gemella("https://x/c/simulation.png"))
check("T1-wp", grabber.gemella("https://x/foto-300x200.jpg?v=2") == grabber.gemella("https://x/foto.jpg"))
check("T1-diverse", grabber.gemella("https://x/a1.jpg") != grabber.gemella("https://x/a2.jpg"))
check("T1-cartelle-diverse", grabber.gemella("https://x/123/large.jpg") != grabber.gemella("https://x/456/large.jpg"))
FILE["/sim.png"] = png(290, 381, (5, 5, 5)); FILE["/sim_2x.png"] = png(580, 762, (5, 5, 5))
HTML["/doppia"] = '<img src="/sim.png" srcset="/sim_2x.png 2x"><p><a href="/sim.png">link</a></p><img src="/h.jpg">'
HTML["/doppia2"] = '<img src="/sim.png"><img src="/sim_2x.png"><img src="/h.jpg">'
r = grabber.scarica_tutto(SITO + "/doppia2", DL, "Gemelle")
g = sorted(x.name for x in (DL / "Gemelle").iterdir())
check("T2-una-sola", g == ["001.png", "002.jpg"] and impronta((DL / "Gemelle" / "001.png").read_bytes()) == impronta(FILE["/sim_2x.png"]), str(g))

# ---- [F] la freccia «avanti»: una foto per pagina, si sfoglia fino alla fine del capitolo
COL = {}
for k in range(1, 12):
    COL[k] = jpg(500, 700, (20 * k, 255 - 20 * k, (k * 70) % 255))
    FILE[f"/pag{k}.jpg"] = COL[k]
FILE["/logo-lettore.png"] = png(300, 80, (1, 1, 1))
L = "/lettore/x/chapter-5"
TESTA = '<img src="/logo-lettore.png"><a href="/lettore/x/chapter-6">Ch 6</a><a href="/lettore/x/chapter-4">Ch 4</a>'
HTML[L] = TESTA + '<img src="/pag1.jpg"><a class="btn next-btn" href="' + L + '/2"><i class="fa fa-chevron-right"></i></a><a href="/lettore/x/chapter-6">Next chapter</a>'
HTML[L + "/2"] = TESTA + '<a href="' + L + '/3"><img src="/pag2.jpg"></a><a href="' + L + '">‹</a>'
HTML[L + "/3"] = TESTA + ('<img src="/pag3.jpg"><select><option value="' + L + '/2">2</option>'
                          '<option selected value="' + L + '/3">3</option><option value="' + L + '/4">4</option></select>')
HTML[L + "/4"] = ('<html><head><link rel="next" href="' + L + '/5"></head><body>' + TESTA + '<img src="/pag4.jpg"></body></html>')
HTML[L + "/5"] = TESTA + '<img src="/pag5.jpg"><a href="/lettore/x/chapter-6">Next ›</a><a href="/lettore/x/chapter-6/1">»</a>'
HTML["/lettore/x/chapter-6"] = '<img src="/pag9.jpg">'
HTML["/lettore/x/chapter-6/1"] = '<img src="/pag9.jpg">'
r = grabber.scarica_tutto(SITO + L, DL, "Freccia")
f = sorted((DL / "Freccia").iterdir())
check("F1-tutte-in-ordine", [impronta(x.read_bytes()) for x in f] == [impronta(COL[k]) for k in range(1, 6)],
      str([x.name for x in f]))
check("F1-nomi", [x.name for x in f] == [f"00{k}.jpg" for k in range(1, 6)], str([x.name for x in f]))
check("F1-conta", r.sfogliate == 5 and r.salvate == 5 and r.arredo >= 1 and not r.fermata, f"{r.sfogliate} {r.salvate} {r.arredo} {r.fermata}")
check("F1-non-salta-capitolo", CHIESTE.get("/lettore/x/chapter-6", 0) == 0 and CHIESTE.get("/lettore/x/chapter-6/1", 0) == 0)

# ?page=N e «Next» scritto
HTML["/q/read?ch=7"] = '<img src="/pag6.jpg"><a href="/q/read?ch=7&page=2">Next</a><a href="/q/read?ch=8">Next chapter</a>'
HTML["/q/read?ch=7&page=2"] = '<img src="/pag7.jpg"><a href="/q/read?ch=8">Next</a>'
HTML["/q/read?ch=8"] = '<img src="/pag9.jpg">'
r = grabber.scarica_tutto(SITO + "/q/read?ch=7", DL, "Query")
f = sorted((DL / "Query").iterdir())
check("F2-query", [impronta(x.read_bytes()) for x in f] == [impronta(COL[6]), impronta(COL[7])], str([x.name for x in f]))

# link senza numeri (codici): la freccia vale solo su una pagina da una foto
HTML["/h/abc"] = '<img src="/pag8.jpg"><a class="arrow-right" href="/h/def"></a>'
HTML["/h/def"] = '<img src="/pag10.jpg"><a href="/h/abc">‹</a>'
r = grabber.scarica_tutto(SITO + "/h/abc", DL, "Codici")
check("F3-codici", len(list((DL / "Codici").iterdir())) == 2 and r.sfogliate == 2)

# capitolo intero su una pagina con «Next ›» al capitolo dopo: NON si segue (come prima)
_prima = CHIESTE.get("/manga/x/chapter-2", 0)
r = grabber.scarica_tutto(SITO + "/manga/x/chapter-1", DL, "Cap intero")
check("F4-capitolo-intero", r.sfogliate == 0 and CHIESTE.get("/manga/x/chapter-2", 0) == _prima and sorted(x.name for x in (DL / "Cap intero").iterdir()) == ["001.png", "002.jpg", "003.jpg"],
      f"{r.sfogliate} {sorted(x.name for x in (DL / 'Cap intero').iterdir())}")
HTML["/manga/y/5"] = "".join(f'<img src="/pag{k}.jpg">' for k in (1, 2, 3, 4)) + '<a class="next" href="/manga/y/6">›</a>'
HTML["/manga/y/6"] = '<img src="/pag11.jpg">'
r = grabber.scarica_tutto(SITO + "/manga/y/5", DL, "Numeri nudi")
check("F5-numeri-nudi-pieno", r.sfogliate == 0 and CHIESTE.get("/manga/y/6", 0) == 0)

# più pagine: ogni capitolo sfogliato nella sua cartella, senza entrare negli altri
HTML["/lettore/elenco"] = '<a href="/lettore/x/chapter-5">5</a><a href="/lettore/x/chapter-6">6</a><a href="/lettore/x/chapter-4">4</a>'
HTML["/lettore/x/chapter-4"] = TESTA + '<img src="/pag11.jpg">'
pag = grabber.cerca_pagine(SITO + "/lettore/elenco")
r = grabber.scarica_pagine(pag, DL, "Lettore")
c5 = DL / "Lettore" / "1 chapter-5"
c5 = next((d for d in (DL / "Lettore").iterdir() if d.name.endswith("chapter-5")), None)
check("F6-capitolo-sfogliato", c5 is not None and [impronta(x.read_bytes()) for x in sorted(c5.iterdir())] == [impronta(COL[k]) for k in range(1, 6)],
      str(sorted(x.name for x in (DL / "Lettore").rglob("*"))))
check("F6-tre-cartelle", r.pagine_fatte == 3, str(r.pagine_fatte))

# unità: forma dei link
check("F7-passo", grabber._passo("https://s/c-5", "https://s/c-5/2") == 1 and grabber._passo("https://s/c-5/2", "https://s/c-5/3") == 1
      and grabber._passo("https://s/c-5.html", "https://s/c-5-2.html") == 1 and grabber._passo("https://s/c-5/9", "https://s/c-6/1") is None)
check("F7-capitolo", grabber._stesso_capitolo("https://s/m/chapter-5", "https://s/m/chapter-5/page/2", False)
      and not grabber._stesso_capitolo("https://s/m/chapter-5", "https://s/m/chapter-6", True)
      and grabber._stesso_capitolo("https://s/m/chapter-5/7", "https://s/m/chapter-5/8", False)
      and not grabber._stesso_capitolo("https://s/series/123/chapter-5", "https://s/series/123/chapter-6", False))

# ---- [N] link nascosti: menu a tendina, data-href, onclick, sottodomini
html_n = ('<select onchange="go(this)"><option value="">Pick</option><option value="/m/chapter-1">Ch 1</option>'
          '<option value="https://w1.sito.com/m/chapter-2">Ch 2</option><option value="3">3</option></select>'
          '<div data-href="/m/chapter-3">Ch 3</div><button onclick="location.href=\'/m/chapter-4\'">Ch 4</button>'
          '<a href="https://altro.example/m/chapter-9">x</a>')
nomi = [p.nome for p in grabber.trova_pagine(html_n, "https://www.sito.com/m/indice")]
check("N1-nascosti", nomi == ["chapter-1", "chapter-2", "chapter-3", "chapter-4"], str(nomi))
check("N2-dominio", grabber._dominio("https://m.sito.co.uk/x") == "sito.co.uk" and grabber._dominio("http://127.0.0.1:8/x") == "127.0.0.1")

# ---- [Q] la finestra vera, gesto vero
from PyQt6.QtWidgets import QApplication
from PyQt6.QtTest import QTest
from PyQt6.QtCore import Qt, QPoint
import app as grabapp
qa = QApplication(sys.argv)
w = grabapp.Finestra(); w.show(); w.resize(560, 420); qa.processEvents()
check("Q1-madre", w.madre == DL, str(w.madre))
check("Q1-dove-largo", w.dove.width() > 100, str(w.dove.width()))
# senza nome: errore a schermo, niente parte
QTest.keyClicks(w.link, SITO + "/pagina")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
check("Q2-serve-nome", not w.scarichi and "name" in w.esito.text(), w.esito.text())

def aspetta(w, quanto=30):
    fine = time.time() + quanto
    while w.al_lavoro() and time.time() < fine:
        qa.processEvents(); time.sleep(0.02)
    qa.processEvents()

QTest.keyClicks(w.nome, "Dalla finestra")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
check("Q3-modulo-vuoto", w.link.text() == "" and w.nome.text() == "" and w.link.isEnabled() and w.vai.text() == "Download images",
      f"{w.link.text()!r} {w.nome.text()!r} {w.vai.text()}")
check("Q3-una-scheda", len(w.scarichi) == 1 and not w.box_scarichi.isHidden(), str(len(w.scarichi)))
aspetta(w)
sc = w.scarichi[0] if w.scarichi else None
check("Q3-finito", sc is not None and sc.stato == "fine", sc.stato if sc else "nessuna")
check("Q3-cartella", len(list((DL / "Dalla finestra").iterdir())) == 8 if (DL / "Dalla finestra").exists() else False)
check("Q3-esito", sc is not None and sc.esito.text().startswith("8 images saved"), sc.esito.text() if sc else "")
check("Q3-apri", sc is not None and not sc.apri.isHidden() and sc.annulla.isHidden() and not sc.togli.isHidden())
QTest.mouseClick(sc.togli, Qt.MouseButton.LeftButton); qa.processEvents()
check("Q3-togli", not w.scarichi and w.box_scarichi.isHidden() and (DL / "Dalla finestra").exists())
w.close()

# ---- [QM] modo «più pagine» nella finestra, gesti veri
w = grabapp.Finestra(); w.show(); w.resize(560, 640); qa.processEvents()
QTest.keyClicks(w.link, SITO + "/indice")
QTest.mouseClick(w.molte, Qt.MouseButton.LeftButton); qa.processEvents()
check("QM1-bottone-cerca", w.vai.text() == "Find pages", w.vai.text())
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); aspetta(w)
check("QM2-elenco", w.elenco.count() == 7 and not w.box_elenco.isHidden(), str(w.elenco.count()))
spuntate = [w.elenco.item(i).text() for i in range(w.elenco.count()) if w.elenco.item(i).checkState() == Qt.CheckState.Checked]
check("QM2-indovinate", spuntate == ["chapter-1", "chapter-2", "chapter-3", "chapter-4"], str(spuntate))
check("QM2-bottone", w.vai.text() == "Download from 4 pages", w.vai.text())
check("QM2-link-intero-nel-suggerimento", w.elenco.item(3).toolTip().endswith("/manga/x/chapter-1"), w.elenco.item(3).toolTip())
def clic_riga(i):
    r = w.elenco.visualItemRect(w.elenco.item(i))
    QTest.mouseClick(w.elenco.viewport(), Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier, r.center()); qa.processEvents()
def clic_quadratino(i):
    r = w.elenco.visualItemRect(w.elenco.item(i))
    QTest.mouseClick(w.elenco.viewport(), Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier, r.topLeft() + QPoint(12, r.height() // 2)); qa.processEvents()
clic_riga(4)                      # tolgo chapter-2 cliccando sul testo
clic_quadratino(1)                # aggiungo about cliccando sul quadratino: un clic = un giro, mai due
check("QM3-un-clic-un-giro", w.elenco.item(1).checkState() == Qt.CheckState.Checked)
clic_quadratino(1)                # e lo ritolgo
check("QM3-ritolta", w.elenco.item(1).checkState() == Qt.CheckState.Unchecked)
check("QM3-tolta", w.elenco.item(4).checkState() == Qt.CheckState.Unchecked)
check("QM3-bottone", w.vai.text() == "Download from 3 pages", w.vai.text())
QTest.keyClicks(w.nome, "Manga dalla finestra")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
check("QM5-azzera-dopo-partenza", w.box_elenco.isHidden() and w.molte.isChecked() and w.vai.text() == "Find pages", w.vai.text())
aspetta(w)
rf = DL / "Manga dalla finestra"
sotto = sorted(x.name for x in rf.iterdir()) if rf.exists() else []
check("QM4-cartelle", sotto == ["01 chapter-1", "02 chapter-3"], str(sotto))
check("QM4-esito", "2 folders" in w.scarichi[0].esito.text() and "chapter-4" in w.scarichi[0].esito.text(), w.scarichi[0].esito.text())
QTest.mouseClick(w.solo, Qt.MouseButton.LeftButton); qa.processEvents()
check("QM5-modo-solo", w.vai.text() == "Download images", w.vai.text())
# cambiare il link azzera l'elenco
QTest.mouseClick(w.molte, Qt.MouseButton.LeftButton); QTest.keyClicks(w.link, SITO + "/indice")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); aspetta(w)
QTest.keyClicks(w.link, "x"); qa.processEvents()
check("QM5-azzera", w.box_elenco.isHidden() and w.vai.text() == "Find pages", w.vai.text())
w.close()

# ---- [QP] più scarichi insieme: il secondo parte mentre il primo va; oltre 3 si aspetta il turno
for k in range(1, 6):
    HTML[f"/lenta/{k}"] = f'<img src="/lenta{k}.jpg">'
    FILE[f"/lenta{k}.jpg"] = jpg(220, 160 + k, (k * 40, 90, 20))
    LENTE[f"/lenta/{k}"] = 0.8
w = grabapp.Finestra(); w.show(); w.resize(560, 900); qa.processEvents()
for k in range(1, 5):
    QTest.keyClicks(w.link, f"{SITO}/lenta/{k}"); QTest.keyClicks(w.nome, f"Lenta {k}")
    QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
stati = [sc.stato for sc in reversed(w.scarichi)]
check("QP1-tre-insieme", stati == ["va", "va", "va", "attesa"], str(stati))
check("QP1-attesa-detta", "Waiting" in w.scarichi[0].esito.text() and "3 running · 1 waiting" in w.conta_scarichi.text(), w.conta_scarichi.text())
# stesso nome di uno che sta andando: rifiutato
QTest.keyClicks(w.link, f"{SITO}/lenta/5"); QTest.keyClicks(w.nome, "Lenta 2")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
check("QP2-stesso-nome", len(w.scarichi) == 4 and "Already downloading" in w.esito.text(), w.esito.text())
w.nome.clear()
aspetta(w)
fatte = [sorted(x.name for x in (DL / f"Lenta {k}").iterdir()) if (DL / f"Lenta {k}").exists() else [] for k in range(1, 5)]
check("QP3-tutte-finite", all(sc.stato == "fine" for sc in w.scarichi) and fatte == [["001.jpg"]] * 4, str(fatte))
check("QP3-foto-giuste", all(impronta((DL / f"Lenta {k}" / "001.jpg").read_bytes()) == impronta(FILE[f"/lenta{k}.jpg"]) for k in range(1, 5)))
w.grab().save(str(RADICE / "shot.png")) if os.environ.get("GRAB_SHOT") else None
if os.environ.get("GRAB_SHOT"):
    import shutil as _sh; _sh.copy(RADICE / "shot.png", os.environ["GRAB_SHOT"])
# annullare uno in fila: non parte mai
for k in (1, 2, 3, 4):
    QTest.keyClicks(w.link, f"{SITO}/lenta/{k}"); QTest.keyClicks(w.nome, f"Fila {k}")
    QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); qa.processEvents()
quarta = w.scarichi[0]
QTest.mouseClick(quarta.annulla, Qt.MouseButton.LeftButton); qa.processEvents()
aspetta(w)
check("QP4-annullata-in-fila", quarta.stato == "fine" and not (DL / "Fila 4").exists() and (DL / "Fila 3" / "001.jpg").exists(),
      quarta.esito.text())
w.close()
LENTE.clear()

# ---- [QB] il bottone nella scheda, gesto vero
w = grabapp.Finestra(); w.show(); w.resize(560, 640); qa.processEvents()
INCIAMPI["/torna.jpg"] = 99
QTest.keyClicks(w.link, SITO + "/buchi"); QTest.keyClicks(w.nome, "Buchi finestra")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); aspetta(w)
sc = w.scarichi[0]
check("QB1-bottone", not sc.riprova.isHidden() and "1 picture missing" in sc.esito.text() and "dead" not in sc.esito.text(), sc.esito.text())
INCIAMPI.clear()
QTest.mouseClick(sc.riprova, Qt.MouseButton.LeftButton); aspetta(w)
check("QB2-tornata", (DL / "Buchi finestra" / "002.jpg").exists() and sc.riprova.isHidden() and "missing" not in sc.esito.text(), sc.esito.text())
w.close()

# ---- [QD] link morto nella scheda: detto a parte, niente bottone, verde perché qualcosa è salvato
w = grabapp.Finestra(); w.show(); w.resize(560, 640); qa.processEvents()
QTest.keyClicks(w.link, SITO + "/morta"); QTest.keyClicks(w.nome, "Morta finestra")
QTest.mouseClick(w.vai, Qt.MouseButton.LeftButton); aspetta(w)
sc = w.scarichi[0]
check("QD1-niente-bottone", sc.riprova.isHidden(), sc.esito.text())
check("QD1-detto", "1 dead link on the site" in sc.esito.text() and "missing" not in sc.esito.text(), sc.esito.text())
check("QD1-verde", sc.esito.property("stato") == "ok", str(sc.esito.property("stato")))
w.close()

print(f"{OK} OK, {FAIL} FAIL")
import shutil; shutil.rmtree(RADICE, ignore_errors=True)
print("TUTTO OK" if FAIL == 0 else "ROSSO")
sys.stdout.flush()
os._exit(0 if FAIL == 0 else 1)
