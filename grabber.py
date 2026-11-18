"""151 Grabber — il cuore senza Qt.

Dato un link: legge la pagina, trova le immagini nell'ordine in cui la pagina
le mostra, le scarica in una cartella col nome scelto e le chiama
001.jpg, 002.png, ... in quell'ordine.

Segue da solo la freccia «avanti» dei siti che mostrano una foto per pagina,
restando nello stesso capitolo.

Non fa: video, pagine dietro login, pagine che caricano le foto solo
scorrendo (servirebbe un browser).
"""
from __future__ import annotations

import base64
import hashlib
import io
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urljoin, urldefrag, unquote, urlsplit

import requests
from requests.adapters import HTTPAdapter
from bs4 import BeautifulSoup

try:
    from PIL import Image
except ImportError:  # import opzionale mai muto: lo dice lo schermo
    Image = None

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")   # identità comune di Chrome, niente di personale
STRISCIA_MAX = 0.15       # una striscia è alta al massimo il 15% della pagina sopra (stessa larghezza)
DETTAGLIO_MIN = 20        # sul taglio ci dev'essere disegno (vere: 94-118): bianco contro bianco non prova niente
SALDATURA_MAX = 75        # differenza media di grigio sul taglio: vere 11-60, false 95-133 (una serie 1190)
MIN_LATO = 48            # sotto questo, su entrambi i lati: icona o pixel spia
MAX_BYTES = 80 * 1024 * 1024
TIMEOUT = 30
INSIEME = 8              # capitoli nello stesso momento (modo «più pagine»); il sito che dice «rallenta» ne toglie
_RALLENTA = [0]          # quante volte un sito ha risposto 429 «troppo in fretta»: il freno dei capitoli lo legge
_LOCK_IMPRONTE = threading.Lock()   # più capitoli insieme: «già salvata?» e «ora è salvata» in un gesto solo
IMG_EXT = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp", ".tif", ".tiff", ".jfif")
LETTORE_MIN = 5          # foto numerate nella stessa cartella perché sia il lettore di un capitolo
LAZY_ATTRS = ("data-src", "data-lazy-src", "data-original", "data-lazy", "data-url", "data-full", "data-hi-res-src")
SRCSET_ATTRS = ("srcset", "data-srcset", "data-lazy-srcset")
_BG_RE = re.compile(r"url\(\s*['\"]?([^'\")]+)['\"]?\s*\)", re.I)


class GrabError(Exception):
    """Errore da mostrare a schermo così com'è. riprova=True: il sito ha inciampato
    (rete, 429 «rallenta», 5xx) e vale la pena riprovare; attesa = Retry-After del sito."""

    def __init__(self, msg, riprova=False, attesa=None, morta=False):
        super().__init__(msg)
        self.riprova, self.attesa = riprova, attesa
        self.morta = morta   # il sito dice che non esiste più (404/410): riprovare non serve


ATTESE = (2, 5, 12)   # secondi fra un tentativo e l'altro


def _attesa_sito(r) -> float | None:
    v = r.headers.get("Retry-After", "")
    return min(float(v), 30.0) if v.strip().isdigit() else None


def con_riprova(fare, stop: threading.Event, tentativi: int = 3):
    """Rifà `fare()` quando il sito inciampa; un no vero (404, non è una pagina) passa subito."""
    for n in range(tentativi):
        try:
            return fare()
        except GrabError as e:
            if not e.riprova or n == tentativi - 1 or stop.is_set():
                raise
            if stop.wait(e.attesa if e.attesa is not None else ATTESE[min(n, len(ATTESE) - 1)]):
                raise


@dataclass
class Risultato:
    cartella: Path
    salvate: int = 0
    trovate: int = 0
    saltate_piccole: int = 0
    doppie: int = 0
    strisce: int = 0                               # strisce di fondo cucite alla loro pagina
    fallite: list = field(default_factory=list)   # (url, motivo)
    annullato: bool = False
    pagine_fatte: int = 0                          # solo «più pagine»
    pagine_vuote: list = field(default_factory=list)
    pagine_fallite: list = field(default_factory=list)   # pagine che il sito non ha dato
    arredo: int = 0                                # foto ripetute su più pagine, lasciate fuori
    mancanti: list = field(default_factory=list)   # Mancante: cosa riprovare col bottone
    sfogliate: int = 0                             # pagine passate con la freccia «avanti» (prima compresa)
    fermata: str = ""                              # perché lo sfoglio si è fermato prima della fine
    rallentato: bool = False                       # il sito ha detto «troppo in fretta»: meno capitoli insieme
    _arredo: frozenset = frozenset()               # contesto per riprovare
    _impronte: set = field(default_factory=set)


@dataclass
class Mancante:
    """Una foto (o una pagina intera) che non è arrivata: il suo posto resta libero.
    Foto: `stem` è il numero riservato («004»), `urls` i candidati. Pagina: `pagina` è la Pagina,
    `cartella` la sottocartella col numero riservato."""
    cartella: Path
    urls: list
    referer: str = ""
    stem: str | None = None
    pagina: object = None
    morta: bool = False   # il sito dice che non esiste più: niente bottone, solo detto


@dataclass
class Buco:
    """Il posto di una foto che non è arrivata, dentro la lista dei posti di una pagina."""
    urls: list
    morta: bool = False


# ---------------------------------------------------------------- nomi

def nome_cartella_pulito(nome: str) -> str:
    nome = re.sub(r"[\x00-\x1f/\\]", " ", nome or "")
    nome = re.sub(r"\s+", " ", nome).strip().strip(".")
    return nome[:120].strip()


def cartella_di_arrivo(madre: Path, nome: str) -> Path:
    pulito = nome_cartella_pulito(nome)
    if not pulito:
        raise GrabError("Type a name for the folder.")
    dest = Path(madre) / pulito
    if dest.exists() and (not dest.is_dir() or any(dest.iterdir())):
        raise GrabError(f"A folder called “{pulito}” already exists and isn't empty: pick another name.")
    return dest


# ---------------------------------------------------------------- pagina

def _migliore_da_srcset(valore: str) -> str | None:
    """Dal srcset prende la versione più grande (w più alto, o x più alto)."""
    migliore, punti = None, -1.0
    for pezzo in re.split(r",\s+(?=\S)", valore.strip()):
        parti = pezzo.strip().split()
        if not parti:
            continue
        url, peso = parti[0], 1.0
        if len(parti) > 1:
            d = parti[1].lower()
            try:
                if d.endswith("w"):
                    peso = float(d[:-1])
                elif d.endswith("x"):
                    peso = float(d[:-1]) * 1000  # 2x batte 1x; con le w non si mescola di solito
            except ValueError:
                pass
        if peso > punti:
            migliore, punti = url, peso
    return migliore


def _sembra_immagine(url: str) -> bool:
    percorso = unquote(urldefrag(url)[0].split("?")[0]).lower()
    return percorso.endswith(IMG_EXT)


def _da_img(tag) -> str | None:
    for a in SRCSET_ATTRS:
        if tag.get(a):
            u = _migliore_da_srcset(tag[a])
            if u:
                return u
    for a in LAZY_ATTRS:
        if tag.get(a) and not tag[a].startswith("data:"):
            return tag[a]
    return tag.get("src")


def trova_immagini(html: str, base: str) -> list[list[str]]:
    """Le immagini nell'ordine della pagina, senza doppioni.

    Ogni voce è una lista di candidati: il primo è la versione migliore
    (es. il link alla foto grande), gli altri la riserva (la foto mostrata)
    se il primo non risulta un'immagine."""
    zuppa = BeautifulSoup(html, "html.parser")
    b = zuppa.find("base", href=True)
    if b:
        base = urljoin(base, b["href"])
    visti, ordine = set(), []
    tags = []       # il tag di ogni voce, per riconoscere il lettore
    usati = set()   # id() dei tag già presi tramite il genitore

    def assoluto(u):
        u = u.strip()
        if u.startswith("data:"):
            return u
        u = urldefrag(urljoin(base, u))[0]
        return u if u.startswith(("http://", "https://")) else None

    def metti(u, riserva=None, tag=None):
        if not u:
            return
        u = u.strip()
        if u.startswith("data:"):
            chiave = u
        else:
            u = urldefrag(urljoin(base, u))[0]
            if not u.startswith(("http://", "https://")):
                return
            chiave = u
        if chiave not in visti:
            visti.add(chiave)
            voce = [u]
            r = assoluto(riserva) if riserva else None
            if r and r != u:
                visti.add(r)
                voce.append(r)
            ordine.append(voce)
            tags.append(tag)

    for tag in zuppa.find_all(True):
        if id(tag) in usati:
            continue
        nome = tag.name
        if nome in ("script", "style", "noscript", "template"):
            for t in tag.find_all(True):
                usati.add(id(t))
            continue
        if nome == "a" and tag.get("href") and _sembra_immagine(tag["href"]):
            # miniatura che porta alla foto grande: si prende la grande
            dentro = tag.find("img")
            mostrata = None
            if dentro is not None:
                mostrata = _da_img(dentro)
                usati.add(id(dentro))
                for t in dentro.find_parents("picture"):
                    usati.add(id(t))
                for s in tag.find_all("source"):
                    usati.add(id(s))
            metti(tag["href"], mostrata, tag)
        elif nome == "picture":
            scelta = None
            for s in tag.find_all("source"):
                usati.add(id(s))
                if not scelta and s.get("srcset"):
                    scelta = _migliore_da_srcset(s["srcset"])
            img = tag.find("img")
            if img is not None:
                usati.add(id(img))
                scelta = _da_img(img) or scelta
            metti(scelta, tag=tag)
        elif nome == "img":
            metti(_da_img(tag), tag=tag)
        if tag.get("style") and "url(" in tag["style"]:
            for u in _BG_RE.findall(tag["style"]):
                if not u.strip().startswith("#"):   # url(#id) è un riferimento interno, non una foto
                    metti(u, tag=tag)
    return _solo_lettore(ordine, tags)


def _solo_lettore(voci: list, tags: list) -> list:
    """Pagina di un capitolo: le sue pagine sono foto numerate nella stessa cartella
    (monster/45/0.webp, 1.webp, …) dentro un contenitore, il lettore. Se c'è, resta solo
    ciò che sta nel lettore: fuori il logo e le miniature dei manga «consigliati».
    Nessun lettore (o il lettore è la pagina intera) → tutto com'era."""
    gruppi: dict = {}
    for i, v in enumerate(voci):
        if v[0].startswith("data:") or tags[i] is None:
            continue
        pz = urlsplit(v[0])
        cartella, _, nome = pz.path.rpartition("/")
        if re.search(r"\d+$", nome.rsplit(".", 1)[0]):
            gruppi.setdefault((pz.netloc, cartella), []).append(i)
    if not gruppi:
        return voci
    scelto = max(gruppi.values(), key=len)
    if len(scelto) < LETTORE_MIN:
        return voci
    lettore = None
    altri = [{id(t) for t in tags[i].parents} | {id(tags[i])} for i in scelto[1:]]
    for a in [tags[scelto[0]], *tags[scelto[0]].parents]:
        if all(id(a) in s for s in altri):
            lettore = a
            break
    if lettore is None or lettore.name in ("body", "html", "[document]"):
        return voci
    return [v for v, t in zip(voci, tags) if t is not None and (t is lettore or lettore in t.parents)]


def cuci(sopra: bytes, sotto: bytes) -> bytes | None:
    """Certi siti danno ogni pagina come foto + una striscia di fondo.
    `sotto` si attacca a `sopra` solo se è davvero il suo pezzo: stessa larghezza, bassa
    (≤ STRISCIA_MAX dell'altra), sul taglio c'è disegno e i pixel continuano. Una doppia pagina larga non
    si tocca mai: si unisce solo, non si taglia. Torna la pagina intera senza perdite, o None."""
    if Image is None:
        return None
    try:
        with Image.open(io.BytesIO(sopra)) as a, Image.open(io.BytesIO(sotto)) as b:
            (wa, ha), (wb, hb) = a.size, b.size
            if wa != wb or hb >= ha or hb > ha * STRISCIA_MAX:
                return None
            fondo = a.convert("L").crop((0, ha - 1, wa, ha)).tobytes()
            cima = b.convert("L").crop((0, 0, wb, 1)).tobytes()
            media = sum(fondo) / wa
            if (sum((x - media) ** 2 for x in fondo) / wa) ** 0.5 < DETTAGLIO_MIN:
                return None
            if sum(abs(x - y) for x, y in zip(fondo, cima)) / wa >= SALDATURA_MAX:
                return None
            modo = "RGBA" if "A" in a.getbands() or "A" in b.getbands() else "RGB"
            pagina = Image.new(modo, (wa, ha + hb))
            pagina.paste(a.convert(modo), (0, 0))
            pagina.paste(b.convert(modo), (0, ha))
        out = io.BytesIO()
        try:
            pagina.save(out, "WEBP", lossless=True)
        except Exception:
            out = io.BytesIO()
            pagina.save(out, "PNG")
        return out.getvalue()
    except Exception:
        return None


def _unisci_strisce(risultati: list, perse: dict, ris) -> None:
    """Ogni foto arrivata che è la striscia di quella prima finisce dentro di lei."""
    ultima = None
    for j, dati in enumerate(risultati):
        if j in perse:
            ultima = None          # un buco in mezzo: niente da cucire attraverso
            continue
        if not dati:
            continue
        if ultima is not None:
            intera = cuci(risultati[ultima], dati)
            if intera:
                risultati[ultima], risultati[j] = intera, None
                ris.strisce += 1
                continue
        ultima = j


def _referer_per(pagina: str, foto: str) -> str | None:
    """Il Referer come lo manda Chrome (strict-origin-when-cross-origin): la pagina intera
    allo stesso sito, solo la radice del sito a un altro host, niente da https verso http.
    Certi server di foto rifiutano l'indirizzo intero (403)."""
    p, f = urlsplit(pagina), urlsplit(foto)
    if p.scheme == "https" and f.scheme == "http":
        return None
    if (p.scheme, p.netloc) == (f.scheme, f.netloc):
        return urldefrag(pagina)[0]
    return f"{p.scheme}://{p.netloc}/"


# ---------------------------------------------------------------- file

def estensione(dati: bytes) -> str | None:
    if dati[:3] == b"\xff\xd8\xff":
        return ".jpg"
    if dati[:8] == b"\x89PNG\r\n\x1a\n":
        return ".png"
    if dati[:6] in (b"GIF87a", b"GIF89a"):
        return ".gif"
    if dati[:4] == b"RIFF" and dati[8:12] == b"WEBP":
        return ".webp"
    if dati[4:12] in (b"ftypavif", b"ftypavis"):
        return ".avif"
    if dati[:2] == b"BM":
        return ".bmp"
    if dati[:4] in (b"II*\x00", b"MM\x00*"):
        return ".tif"
    return None


_TAGLIA = re.compile(r"([@_-](\d+x\d*|\d*x\d+|scaled|large|medium|small|thumb|thumbnail|orig|original|full|big|hd))+$", re.I)


def gemella(url: str) -> str | None:
    """La stessa foto in un'altra misura ha lo stesso nome senza il pezzo della misura:
    simulation.png / simulation_2x.png, foto-300x200.jpg / foto.jpg, img@2x.png / img.png."""
    if url.startswith("data:"):
        return None
    cartella, _, nome = unquote(urldefrag(url)[0].split("?")[0].rstrip("/")).lower().rpartition("/")
    radice = nome.rsplit(".", 1)[0] if "." in nome else nome
    radice = _TAGLIA.sub("", radice)
    # la cartella fa parte del nome: /123/large.jpg e /456/large.jpg sono due foto diverse
    return f"{cartella}/{radice}" if radice else None


def troppo_piccola(dati: bytes) -> bool:
    if Image is None:
        return False
    try:
        with Image.open(io.BytesIO(dati)) as im:
            w, h = im.size
    except Exception:
        return False  # Pillow non la legge (es. avif): la teniamo
    return w < MIN_LATO and h < MIN_LATO


def _scarica(sess: requests.Session, url: str, referer: str, stop: threading.Event) -> bytes:
    if url.startswith("data:"):
        testa, _, corpo = url.partition(",")
        if ";base64" in testa:
            return base64.b64decode(corpo)
        return unquote(corpo).encode("latin-1", "replace")
    try:
        ref = _referer_per(referer, url) if referer else None
        r = sess.get(url, headers={"Referer": ref} if ref else {}, timeout=TIMEOUT, stream=True)
    except requests.RequestException as e:
        raise GrabError(f"network: {e.__class__.__name__}", riprova=True) from None
    with r:
        if r.status_code != 200:
            if r.status_code == 429:
                _RALLENTA[0] += 1
            raise GrabError(f"the site answered {r.status_code}",
                            riprova=r.status_code == 429 or r.status_code >= 500, attesa=_attesa_sito(r),
                            morta=r.status_code in (404, 410))
        attesi = r.headers.get("Content-Length")
        pezzi, tot = [], 0
        for p in r.iter_content(65536):
            if stop.is_set():
                raise GrabError("cancelled")
            tot += len(p)
            if tot > MAX_BYTES:
                raise GrabError("bigger than 80 MB")
            pezzi.append(p)
        dati = b"".join(pezzi)
        if attesi and attesi.isdigit() and r.headers.get("Content-Encoding") in (None, "identity") and int(attesi) != len(dati):
            raise GrabError("arrived only halfway")
        return dati


def _scrivi_guadagnato(dest: Path, dati: bytes) -> None:
    """nome.part, controllo dimensione, poi il nome buono."""
    part = dest.with_name(dest.name + f".{os.getpid()}.part")
    with open(part, "wb") as f:
        f.write(dati)
        f.flush()
        os.fsync(f.fileno())
    if part.stat().st_size != len(dati):
        part.unlink(missing_ok=True)
        raise GrabError("incomplete write")
    if dest.exists():
        part.unlink(missing_ok=True)
        raise GrabError(f"{dest.name} already exists")
    os.replace(part, dest)


# ---------------------------------------------------------------- il giro

def nuova_sessione() -> requests.Session:
    s = requests.Session()
    # capitoli insieme × foto insieme: la piscina di connessioni deve bastare per tutti
    a = HTTPAdapter(pool_connections=8, pool_maxsize=INSIEME * 6 + 4)
    s.mount("https://", a); s.mount("http://", a)
    s.headers.update({"User-Agent": UA, "Accept-Language": "it,en;q=0.8"})
    return s


def leggi_pagina(sess: requests.Session, link: str) -> tuple[str, str]:
    link = (link or "").strip()
    if not link:
        raise GrabError("Paste a link.")
    if not re.match(r"^https?://", link, re.I):
        link = "https://" + link
    try:
        r = sess.get(link, timeout=TIMEOUT, headers={"Accept": "text/html,*/*"})
    except requests.RequestException as e:
        raise GrabError(f"Can't open the page ({e.__class__.__name__}).", riprova=True) from None
    if r.status_code != 200:
        if r.status_code == 429:
            _RALLENTA[0] += 1
        raise GrabError(f"The page answered {r.status_code}.",
                        riprova=r.status_code == 429 or r.status_code >= 500, attesa=_attesa_sito(r))
    tipo = r.headers.get("Content-Type", "")
    if tipo.startswith("image/"):
        raise GrabError("This link is a single image, not a page.")
    if r.encoding is None or r.encoding.lower() == "iso-8859-1":
        r.encoding = r.apparent_encoding
    return r.text, r.url


def _raccogli(sess, html: str, base: str, ris: Risultato, impronte: set, stop: threading.Event,
              avanzamento, paralleli: int = 6, prefisso: str = "", escludi: frozenset = frozenset()) -> list:
    """Scarica le immagini di una pagina già letta; torna i «posti» in ordine: (dati, ext) per
    le foto arrivate, una lista di candidati per quelle che non sono arrivate (il loro numero
    resta libero). Senza icone né doppioni (anche verso `impronte`, che si riempie) né `escludi`."""
    urls = [v for v in trova_immagini(html, base) if v[0] not in escludi]
    ris.trovate += len(urls)
    if not urls:
        return []
    risultati: list = [None] * len(urls)
    perse: dict = {}   # indice -> True se il link è morto sul sito
    fatte = 0
    lock = threading.Lock()

    def uno(i):
        nonlocal fatte
        motivo = None
        morte = 0
        for u in urls[i]:
            if stop.is_set():
                break
            try:
                dati = con_riprova(lambda: _scarica(sess, u, base, stop), stop, 2)
            except Exception as e:
                motivo = str(e) or e.__class__.__name__
                morte += 1 if getattr(e, "morta", False) else 0
                continue
            if estensione(dati):
                risultati[i] = dati
                break
            motivo = motivo or "not an image"
        if risultati[i] is None and not stop.is_set() and motivo != "not an image":
            with lock:
                ris.fallite.append((urls[i][0][:200], motivo))
                perse[i] = morte == len(urls[i])   # True: link morto sul sito, non si riprova
        with lock:
            fatte += 1
            avanzamento(fatte, len(urls), f"{prefisso}Downloading {fatte} of {len(urls)}…")

    with ThreadPoolExecutor(max_workers=paralleli) as ex:
        list(ex.map(uno, range(len(urls))))
    if stop.is_set():
        return []

    # la stessa foto in due misure nella stessa pagina: resta la più grande, al posto della prima
    posto: dict = {}
    for i, dati in enumerate(risultati):
        if not dati:
            continue
        g = gemella(urls[i][0])
        if g is None:
            continue
        if g in posto:
            j = posto[g]
            if len(dati) > len(risultati[j]):
                risultati[j] = dati
            risultati[i] = None
            ris.doppie += 1
        else:
            posto[g] = i

    _unisci_strisce(risultati, perse, ris)

    tenute = []
    for i, dati in enumerate(risultati):
        if i in perse:
            tenute.append(Buco(list(urls[i]), perse[i]))   # posto libero
            continue
        if not dati:
            continue
        ext = estensione(dati)
        if ext is None:
            continue  # non è un'immagine (svg, pagina d'errore, ...)
        imp = hashlib.sha256(dati).hexdigest()
        with _LOCK_IMPRONTE:
            if imp in impronte:
                ris.doppie += 1
                continue
            if troppo_piccola(dati):
                ris.saltate_piccole += 1
                continue
            impronte.add(imp)
        tenute.append((dati, ext))
    return tenute


def _arrivate(posti: list) -> int:
    return sum(1 for x in posti if isinstance(x, tuple))


def _scrivi_cartella(dest: Path, posti: list, ris: Risultato, referer: str = "",
                     inizio: int = 0, cifre: int | None = None) -> int:
    """Numera tutti i posti nell'ordine della pagina: le foto arrivate si scrivono, quelle
    mancate lasciano il loro numero libero (001, 002, 004) e finiscono in ris.mancanti.
    inizio: quanti numeri sono già usati nella cartella (pagine sfogliate prima)."""
    dest.mkdir(parents=True, exist_ok=True)
    cifre = cifre or max(3, len(str(len(posti))))
    scritte = 0
    for n, x in enumerate(posti, inizio + 1):
        stem = f"{n:0{cifre}d}"
        if isinstance(x, tuple):
            dati, ext = x
            _scrivi_guadagnato(dest / f"{stem}{ext}", dati)
            scritte += 1
        else:
            ris.mancanti.append(Mancante(dest, x.urls, referer, stem=stem, morta=x.morta))
    return scritte


def scarica_tutto(link: str, madre: Path, nome: str, avanzamento=None,
                  stop: threading.Event | None = None, paralleli: int = 6) -> Risultato:
    """Una pagina → una cartella. avanzamento(fatte, totale, messaggio). stop: Event per annullare."""
    stop = stop or threading.Event()
    avanzamento = avanzamento or (lambda *a: None)
    dest = cartella_di_arrivo(madre, nome)
    sess = nuova_sessione()
    avanzamento(0, 0, "Reading the page…")
    html, base = leggi_pagina(sess, link)
    ris = Risultato(cartella=dest)
    scritte, numeri = _giro_pagine(sess, html, base, dest, ris, stop, avanzamento, paralleli, cerca_arredo=True)
    if stop.is_set():
        ris.annullato = True
        ris.salvate = scritte
        return ris
    if not ris.trovate:
        raise GrabError("No images found on this page. It may load them only while you scroll.")
    if not numeri:
        raise GrabError("Only icons or non-photo files found: nothing to save.")
    ris.salvate = scritte
    avanzamento(1, 1, "Saved")
    return ris


# ---------------------------------------------------------------- la freccia «avanti»
# Siti che mostrano una foto per pagina: si segue la freccia finché il capitolo finisce.
# «Stesso capitolo» = i numeri del link di partenza restano uguali (tranne il numero di
# pagina), e un link che dice «chapter/episode/post» non si segue mai.

SFOGLIA_MAX = 500
_FRECCIA_TESTO = re.compile(r"(next|next page|next image|avanti|successiva|pagina successiva|seguente|suivant|"
                            r"siguiente|weiter|nächste|próxima|proxima|[›»>→⟩❯▶►⇒⟶⇨]+|-->)", re.I)
_FRECCIA_ATTR = re.compile(r"(?:^|[^a-z])(next|nxt|avanti|forward|succ\w*|arrow-right|chevron-right|angle-right|"
                           r"caret-right|right-arrow)(?:[^a-z]|$)", re.I)
_CAPITOLO_TESTO = re.compile(r"chap|capit|episod|volum|\bvol\b|\bpost\b|article|articolo|stor[yi]", re.I)
_CAPITOLO_ATTR = re.compile(r"chap|capit|episod", re.I)
_PAROLA_PAGINA = re.compile(r"(?:^|[^a-z])(page|pagina|pag|pg|img|image|foto|photo|slide|p)[\s/=_\-]*$", re.I)
_PAROLA_CAPITOLO = re.compile(r"(?:^|[^a-z])(chapter|chap|ch|capitolo|cap|episode|ep|vol|volume|issue|c)[\s/=_.\-]*$", re.I)
_EXT_PAGINA = re.compile(r"\.(html?|php|aspx?)$", re.I)


def _dominio(u: str) -> str:
    """Il sito senza sottodomini: m.sito.com, w1.sito.com e sito.com sono lo stesso sito."""
    h = _host(u)
    if re.fullmatch(r"[\d.]+", h) or ":" in h or h.count(".") < 2:
        return h
    parti = h.split(".")
    if len(parti[-1]) == 2 and parti[-2] in ("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or"):
        return ".".join(parti[-3:])
    return ".".join(parti[-2:])


def _chiave(u: str) -> str:
    sp = urlsplit(u)
    k = sp.path.rstrip("/") or "/"
    return k + ("?" + sp.query if sp.query else "")


def _pezzi(u: str):
    p = re.split(r"(\d+)", _chiave(u))
    return p[0::2], [int(x) for x in p[1::2]]


def _passo(cur: str, nxt: str) -> int | None:
    """Di quanto nxt va avanti rispetto a cur, se ne è la pagina dopo: stessa forma con un solo
    numero cresciuto, oppure cur + un numero in coda (pagina 1 senza numero → /2, ?page=2, -2.html)."""
    tc, nc = _pezzi(cur)
    tn, nn = _pezzi(nxt)
    if tc == tn and len(nc) == len(nn):
        diversi = [i for i in range(len(nc)) if nc[i] != nn[i]]
        if len(diversi) == 1 and nn[diversi[0]] > nc[diversi[0]]:
            return nn[diversi[0]] - nc[diversi[0]]
        return None
    kc, kn = _chiave(cur), _chiave(nxt)
    m = _EXT_PAGINA.search(kc)
    radice = kc[: m.start()] if m else kc
    if kn.startswith(radice):
        m = re.fullmatch(r"[/\-_?&.]?(?:[a-z]+[/=_\-]?)?(\d+)(?:\.(?:html?|php|aspx?))?/?", kn[len(radice):], re.I)
        if m and int(m.group(1)) in (1, 2):
            return 1
    return None


def _stesso_capitolo(partenza: str, u: str, lasco: bool) -> bool:
    """I numeri del link di partenza restano tutti uguali in u, tranne l'ultimo quando è il
    numero di pagina (preceduto da page/p=/img…, o dopo il numero del capitolo, o su una pagina
    con una foto sola). /chapter-5 → /chapter-5/2 sì; /chapter-5 → /chapter-6 no."""
    ts, ns = _pezzi(partenza)
    _, nn = _pezzi(u)
    if len(nn) < len(ns):
        return False
    libero = None
    if ns:
        prima = ts[len(ns) - 1]
        if _PAROLA_PAGINA.search(prima):
            libero = len(ns) - 1
        elif _PAROLA_CAPITOLO.search(prima):
            libero = None
        elif len(ns) >= 2 or lasco:
            libero = len(ns) - 1
    return all(nn[i] == ns[i] for i in range(len(ns)) if i != libero)


def _pulito(u: str) -> str:
    return urldefrag(u)[0].rstrip("/")


def pagina_dopo(html: str, base: str, partenza: str, visti: set, lasco: bool,
                evita: frozenset = frozenset()) -> str | None:
    """Il link della pagina dopo, o None se il capitolo è finito. Prima la freccia
    (rel=next, «Next», ›, classi «next»/«arrow-right», il menu delle pagine) che va
    a un numero dopo; poi un link qualunque al numero +1; la freccia senza numero
    solo sulle pagine da una foto."""
    zuppa = BeautifulSoup(html, "html.parser")
    b = zuppa.find("base", href=True)
    if b:
        base = urljoin(base, b["href"])
    casa, io = _dominio(base), urldefrag(base)[0]

    def buono(h):
        h = (h or "").strip()
        if not h or h.startswith(("#", "javascript:", "mailto:", "tel:", "data:")):
            return None
        u = urldefrag(urljoin(base, h))[0]
        if (not u.startswith(("http://", "https://")) or _dominio(u) != casa or _chiave(u) == _chiave(io)
                or _pulito(u) in visti or _pulito(u) in evita
                or unquote(u.split("?")[0]).lower().endswith(NON_PAGINE)
                or not _stesso_capitolo(partenza, u, lasco)):
            return None
        return u

    frecce, numeri = [], []
    for t in zuppa.find_all(["a", "link"], href=True):
        u = buono(t["href"])
        if not u:
            continue
        rel = [x.lower() for x in (t.get("rel") or [])]
        if t.name == "link" and "next" not in rel:
            continue
        testo = " ".join(t.get_text(" ", strip=True).split())
        attr = []
        for x in [t] + t.find_all(True):
            for a in ("class", "id", "title", "aria-label", "alt"):
                v = x.get(a)
                attr.append(" ".join(v) if isinstance(v, list) else str(v or ""))
        attr = " ".join(attr)
        if _CAPITOLO_TESTO.search(testo) or _CAPITOLO_ATTR.search(attr):
            continue                                    # «Next chapter»: fuori dal capitolo
        freccia = ("next" in rel or (testo and len(testo) <= 25 and _FRECCIA_TESTO.fullmatch(testo))
                   or _FRECCIA_ATTR.search(attr))
        p = _passo(io, u)
        if freccia:
            frecce.append((p is None, p or 0, u))
        elif p == 1:
            numeri.append(u)
    # il menu a tendina delle pagine: l'opzione dopo quella scelta
    for sel in zuppa.find_all("select"):
        opz = sel.find_all("option")
        for i, o in enumerate(opz[:-1]):
            if o.has_attr("selected"):
                v = opz[i + 1].get("value") or ""
                u = buono(v) if ("/" in v or v.startswith("http") or _EXT_PAGINA.search(v)) else None
                if u:
                    p = _passo(io, u)
                    frecce.append((p is None, p or 0, u))
    frecce.sort(key=lambda x: (x[0], x[1]))
    if frecce and not frecce[0][0]:
        return frecce[0][2]
    if numeri:
        return numeri[0]
    if frecce and lasco:
        return frecce[0][2]
    return None


def pagine_seguenti(sess, html: str, base: str, ris: Risultato, stop: threading.Event,
                    evita: frozenset = frozenset()):
    """Le pagine dopo quella data, una alla volta, finché c'è la freccia (al massimo SFOGLIA_MAX)."""
    partenza = base
    lasco = len(trova_immagini(html, base)) <= 3
    visti = {_pulito(base)}
    for n in range(2, SFOGLIA_MAX + 1):
        if stop.is_set():
            return
        u = pagina_dopo(html, base, partenza, visti, lasco, evita)
        if not u:
            return
        visti.add(_pulito(u))
        try:
            html, base = con_riprova(lambda: leggi_pagina(sess, u), stop)
        except GrabError as e:
            if not stop.is_set():
                ris.fermata = f"page {n} didn't open ({e}), stopped there"
            return
        if _pulito(base) in visti and _pulito(base) != _pulito(u):
            return                                      # rimandati a una pagina già vista
        visti.add(_pulito(base))
        yield html, base
    ris.fermata = f"stopped after {SFOGLIA_MAX} pages"


def _giro_pagine(sess, html: str, base: str, dest: Path, ris: Risultato, stop: threading.Event,
                 avanzamento, paralleli: int = 6, prefisso: str = "", escludi: frozenset = frozenset(),
                 evita: frozenset = frozenset(), cerca_arredo: bool = False):
    """Una pagina e quelle dopo (la freccia «avanti») in una cartella sola, numerate di seguito
    e salvate pagina per pagina. Torna (foto scritte, numeri usati)."""
    seguenti = pagine_seguenti(sess, html, base, ris, stop, evita)
    dopo = next(seguenti, None)
    if dopo and cerca_arredo:
        # foto uguali su pagina 1 e 2 (logo, banner) = arredo del sito
        comuni = {v[0] for v in trova_immagini(html, base)} & {v[0] for v in trova_immagini(*dopo)}
        if comuni:
            escludi = escludi | comuni
            ris.arredo += len(comuni)
            ris._arredo = ris._arredo | comuni
    numero = scritte = vuote = con_foto = 0
    cifre, pagine = 3, 1
    corrente = (html, base)
    while corrente:
        h, b = corrente
        pref = f"{prefisso}Page {pagine} · " if dopo or pagine > 1 else prefisso
        posti = _raccogli(sess, h, b, ris, ris._impronte, stop, avanzamento, paralleli, pref, escludi)
        if stop.is_set():
            break
        if posti:
            vuote = 0
            con_foto += 1
            if numero == 0:
                cifre = max(3, len(str(len(posti))))
            if numero + len(posti) >= 10 ** cifre:
                ris.fermata = f"stopped at {numero} pictures (folder full)"
                break
            scritte += _scrivi_cartella(dest, posti, ris, b, numero, cifre)
            numero += len(posti)
        else:
            vuote += 1
            if vuote >= 2:
                break
        if dopo is None:
            break
        corrente, dopo = dopo, next(seguenti, None)
        pagine += 1
    if pagine > 1:
        ris.sfogliate += con_foto   # pagine che hanno dato foto (non quelle vuote in coda)
    return scritte, numero


# ---------------------------------------------------------------- più pagine

NON_PAGINE = IMG_EXT + (".svg", ".pdf", ".zip", ".rar", ".7z", ".mp4", ".mp3", ".webm", ".mov",
                        ".css", ".js", ".json", ".xml", ".rss", ".apk", ".exe", ".dmg")


@dataclass
class Pagina:
    url: str
    nome: str          # l'ultima parte del link, quella che vede chi lo usa
    scelta: bool = True
    testo: str = ""    # il testo del link sulla pagina («Chapter 12»): aiuta indovina


def _host(u: str) -> str:
    from urllib.parse import urlsplit
    h = (urlsplit(u).hostname or "").lower()
    return h[4:] if h.startswith("www.") else h


def _segmenti(u: str) -> list:
    from urllib.parse import urlsplit
    return [unquote(x) for x in urlsplit(u).path.split("/") if x]


def nome_breve(u: str, profondita: int = 1) -> str:
    from urllib.parse import urlsplit
    seg = _segmenti(u)
    q = urlsplit(u).query
    nome = "/".join(seg[-profondita:]) if seg else "home"
    if q:
        nome += "?" + unquote(q)
    return nome[:80]


_ONCLICK_RE = re.compile(r"""(?:location(?:\.href)?|window\.open)\s*(?:=|\()\s*['"]([^'"]+)['"]""", re.I)


def _link_nascosti(zuppa):
    """Ogni link della pagina in ordine, col suo testo: <a href>, le voci dei menu a tendina
    (<option value>), data-href/data-url/data-link e onclick="location.href='…'"."""
    def testo(t):
        return " ".join(t.get_text(" ").split())[:120]
    for t in zuppa.find_all(True):
        if t.name in ("script", "style", "template"):
            continue
        if t.name == "a" and t.get("href"):
            yield t["href"].strip(), testo(t)
        elif t.name == "option":
            v = (t.get("value") or "").strip()
            if v.startswith(("http://", "https://", "/", "./", "../")) or ("/" in v and " " not in v) or _EXT_PAGINA.search(v):
                yield v, testo(t)
        for a in ("data-href", "data-url", "data-link"):
            if t.get(a):
                yield t[a].strip(), testo(t)
        oc = t.get("onclick")
        if oc:
            for m in _ONCLICK_RE.finditer(oc):
                yield m.group(1).strip(), testo(t)


def trova_pagine(html: str, base: str) -> list:
    """I link verso altre pagine dello STESSO sito, nell'ordine della pagina, senza
    doppioni, senza la pagina stessa, senza file (foto, pdf, zip…)."""
    zuppa = BeautifulSoup(html, "html.parser")
    b = zuppa.find("base", href=True)
    if b:
        base = urljoin(base, b["href"])
    io_stessa = urldefrag(base)[0].rstrip("/")
    casa = _dominio(base)
    visti, pagine = set(), []
    for h, testo in _link_nascosti(zuppa):
        if not h or h.startswith(("#", "mailto:", "tel:", "javascript:", "data:")):
            continue
        u = urldefrag(urljoin(base, h))[0]
        if not u.startswith(("http://", "https://")) or _dominio(u) != casa:
            continue
        if u.rstrip("/") == io_stessa or u.rstrip("/") in visti:
            continue
        if unquote(u.split("?")[0]).lower().endswith(NON_PAGINE):
            continue
        visti.add(u.rstrip("/"))
        pagine.append(Pagina(u, nome_breve(u), testo=testo))
    # nomi uguali (es. /a/capitolo-1 e /b/capitolo-1): si allunga finché si distinguono
    for prof in (2, 3):
        conta = {}
        for p in pagine:
            conta[p.nome] = conta.get(p.nome, 0) + 1
        doppi = {n for n, c in conta.items() if c > 1}
        if not doppi:
            break
        for p in pagine:
            if p.nome in doppi:
                p.nome = nome_breve(p.url, prof)
    return pagine


# «chapter-12», «ch12», «c012», «ep-3», «season-2-ep-40», «capitolo_7»: una parola da capitolo
# attaccata a un numero, all'inizio del pezzo o dopo un separatore (non «f212c582», non «step-1»)
_CAP_PEZZO = re.compile(r"(?:^|[-_.~ ])(?:chapter|chap|ch|c|episode|ep|capitolo|cap|capitulo)[-_. ]?\d", re.I)
_CAP_PAROLA = re.compile(r"(?:chapter|chap|ch|c|episode|ep|capitolo|cap|capitulo)", re.I)   # «/chapter/6»
# il testo del link COMINCIA con «Chapter 12» (non «Solo Leveling - Chapter 200», che è la serie)
_CAP_TESTO = re.compile(r"^\W*(?:chapter|chap|ch|episode|ep|capitolo|cap|capitulo)\b\.?\s*#?\d", re.I)


def _norma(pezzo: str) -> str:
    """Un pezzo di link senza ciò che cambia da un capitolo all'altro: i numeri (anche 34.5)
    diventano #, un codice lungo (01M6RHBP…, uuid, esadecimale) diventa *."""
    if re.search(r"\d", pezzo) and re.fullmatch(r"[0-9a-f-]{8,}|[0-9A-Za-z]{16,}", pezzo, re.I):
        return "*"
    return re.sub(r"\d+(?:\.\d+)?", "#", pezzo)


def _norma_cartella(pezzo: str) -> str:
    """Un pezzo di cartella: i numeri e i codici cambiano (/chapters/2-11198000/), un nome resta
    com'è anche se ha un numero (/manga/level-99-hero/ è un'altra serie, non un altro capitolo)."""
    n = _norma(pezzo)
    return n if n == "*" or not re.search(r"[a-z]", pezzo, re.I) else pezzo


def _cartella(seg: list) -> str:
    return "/".join(_norma_cartella(x) for x in seg)


def _domanda(u: str) -> str:
    """La parte dopo «?» senza ciò che cambia: «?episode_no=#&title_no=#»."""
    from urllib.parse import parse_qsl
    return "?" + "&".join(sorted(f"{k}={_norma(v)}" for k, v in parse_qsl(urlsplit(u).query, True)))


def _forma_link(p) -> tuple:
    """(dove sta il pezzo-capitolo o None, cartella normalizzata, coda normalizzata).
    Il pezzo-capitolo è l'ultimo che ha «chapter/ch/c/ep…+numero»; se il link non lo dice
    ma il testo sì («Chapter 12» su /chapters/01M6…), è l'ultimo pezzo."""
    seg = _segmenti(p.url)
    dove = None
    for i in range(len(seg) - 1, -1, -1):
        if _CAP_PEZZO.search(seg[i]) or (i > 0 and _CAP_PAROLA.fullmatch(seg[i - 1]) and re.fullmatch(r"\d+(?:\.\d+)?", seg[i])):
            dove = i
            break
    if dove is None and seg and _CAP_TESTO.search(p.testo or ""):
        dove = len(seg) - 1
    if dove is None:   # l'ultimo pezzo è quello che cambia: lì i numeri contano anche dentro un nome
        return None, "/".join([_cartella(seg[:-1])] * bool(seg[:-1]) + [_norma(x) for x in seg[-1:]]), _domanda(p.url)
    return dove, _cartella(seg[:dove]), "/".join(_norma(x) for x in seg[dove + 1:]) + _domanda(p.url)


def indovina(pagine: list, pagina: str = "") -> list:
    """Spunta solo i link che sembrano «i capitoli» della pagina `pagina`, lascia gli altri
    visibili ma senza spunta. Nessun elenco chiaro → nessuna spunta: non inventa capitoli.
    1) link con «chapter/ch/c/ep…+numero» (nel link o all'inizio del testo), raggruppati per
       cartella e coda, ≥2: vince il gruppo della serie della pagina (cartella dentro il suo
       indirizzo), poi il più grande; entrano i fratelli con un numero nella stessa cartella (side-story-3).
    2) link dentro la cartella della pagina, uguali tranne un pezzo (il titolo dell'episodio)
       e con un numero che cambia (webtoon: /buni/<titolo>/viewer?episode_no=20), ≥3.
    3) link uguali tranne un numero o un codice (xkcd /123/, viewtopic.php?id=), ≥3.
    4) una cartella che da sola ha almeno 10 link e più di metà della pagina (gli articoli di un blog).
    Due gruppi grandi uguali che cambiano solo per la serie (home con «ultimi capitoli») = nessuno."""
    if not pagine:
        return pagine
    pag_seg = _segmenti(pagina) if pagina else []
    pag_norm = [_norma_cartella(x) for x in pag_seg]
    segs = [_segmenti(p.url) for p in pagine]
    forme = [(_host(p.url),) + _forma_link(p) for p in pagine]

    def della_pagina(cartella: str) -> bool:
        """La cartella dei capitoli sta dentro l'indirizzo della pagina: /manga/one-piece/… su
        /manga/one-piece. Una cartella sola (/manga/…) vale solo se è tutta la pagina (/one-piece)."""
        c = cartella.split("/") if cartella else []
        return bool(c) and pag_norm[:len(c)] == c and (len(c) >= 2 or len(pag_norm) == 1)

    def simili(a, b) -> bool:   # stesso disegno, cambia solo un pezzo della cartella (la serie)
        ca, cb = a[1].split("/"), b[1].split("/")
        return a[0] == b[0] and a[2] == b[2] and len(ca) == len(cb) and sum(x != y for x, y in zip(ca, cb)) == 1

    def scegli(gruppi: dict, minimo: int, conta_pagina: bool):
        if not gruppi:
            return None
        ordine = sorted(gruppi, key=lambda k: (conta_pagina and della_pagina(k[1]), len(gruppi[k]), -gruppi[k][0]), reverse=True)
        k = ordine[0]
        mia = conta_pagina and della_pagina(k[1])
        if len(gruppi[k]) < (2 if mia else minimo):
            return None
        if not mia and any(len(gruppi[o]) == len(gruppi[k]) and simili(o, k) for o in ordine[1:]):
            return None   # tante serie con lo stesso numero di capitoli: non si sa quale
        return k

    def spunta(scelti) -> list:
        for i, p in enumerate(pagine):
            p.scelta = i in scelti
        return pagine

    # 1) capitoli dichiarati
    capitoli = {}
    for i, (host, dove, cartella, coda) in enumerate(forme):
        if dove is not None:
            capitoli.setdefault((host, cartella, coda), []).append(i)
    k = scegli(capitoli, 2, True)
    if k is not None:
        scelti = set(capitoli[k])
        prof = len(k[1].split("/")) if k[1] else 0
        for i, seg in enumerate(segs):   # i fratelli: side-story-3, extra-1
            if (forme[i][0] == k[0] and len(seg) > prof and _cartella(seg[:prof]) == k[1]
                    and "/".join(_norma(x) for x in seg[prof + 1:]) + _domanda(pagine[i].url) == k[2]
                    and re.search(r"\d", seg[prof])):
                scelti.add(i)
        return spunta(scelti)
    # 2) dentro la cartella della pagina, cambia un pezzo (il titolo) e c'è un numero che cambia
    lp = len(pag_seg) - 1
    if lp >= 2:
        gruppi = {}
        for i, seg in enumerate(segs):
            if len(seg) > lp and seg[:lp] == pag_seg[:lp] and forme[i][0] == _host(pagina):
                resto = "/".join(_norma(x) for x in seg[lp + 1:]) + _domanda(pagine[i].url)
                if "#" in resto or "*" in resto or re.search(r"\d", seg[lp]):
                    gruppi.setdefault((len(seg), resto), []).append(i)
        if gruppi:
            g = max(gruppi.values(), key=len)
            if len(g) >= 3:
                return spunta(set(g))
    # 3) uguali tranne un numero o un codice
    altri = {}
    for i, (host, dove, cartella, coda) in enumerate(forme):
        if re.search(r"[#*]", cartella + coda):
            altri.setdefault((host, cartella, coda), []).append(i)
    k = scegli(altri, 3, False)
    if k is not None:
        return spunta(set(altri[k]))
    # 4) una cartella che è più di metà della pagina
    cartelle = {}
    for i, seg in enumerate(segs):
        cartelle.setdefault((forme[i][0], _cartella(seg[:-1]), len(seg)), []).append(i)
    g = max(cartelle.values(), key=len)
    if len(g) >= 10 and len(g) * 2 > len(pagine):
        return spunta(set(g))
    return spunta(set())


def cerca_pagine(link: str) -> list:
    sess = nuova_sessione()
    html, base = leggi_pagina(sess, link)
    pagine = indovina(trova_pagine(html, base), base)
    if not pagine:
        raise GrabError("This page has no links to other pages of the same site.")
    return pagine


def scarica_pagine(pagine: list, madre: Path, nome: str, avanzamento=None,
                   stop: threading.Event | None = None, paralleli: int = 6,
                   insieme: int | None = None) -> Risultato:
    """Le pagine scelte → una cartella madre col nome dato, dentro «01 chapter-1», «02 …».
    `insieme` pagine (capitoli) alla volta. Il numero della cartella è il posto della pagina
    nell'elenco scelto, qualunque finisca prima. Una foto già salvata da un'altra pagina non si ripete."""
    stop = stop or threading.Event()
    avanzamento = avanzamento or (lambda *a: None)
    insieme = max(1, insieme or INSIEME)
    scelte = [p for p in pagine if p.scelta]
    if not scelte:
        raise GrabError("No pages selected.")
    dest = cartella_di_arrivo(madre, nome)
    sess = nuova_sessione()
    ris = Risultato(cartella=dest)
    N = len(scelte)
    cifre = max(2, len(str(N)))

    # 1) le prime pagine (al massimo 3) si leggono subito: una foto che compare in più
    #    di una è l'arredo del sito (logo, banner, menu) e non va in nessuna cartella.
    def leggi(p):
        try:
            return con_riprova(lambda: leggi_pagina(sess, p.url), stop)
        except GrabError as e:
            return e

    base_rallenta = _RALLENTA[0]   # il freno conta i «troppo in fretta» da qui in poi
    lette: dict = {}
    with ThreadPoolExecutor(max_workers=min(insieme, 3)) as ex:
        for p, v in zip(scelte[:3], ex.map(leggi, scelte[:3])):
            lette[id(p)] = v
    campione = [v for v in lette.values() if not isinstance(v, GrabError)]
    conta: dict = {}
    for html, base in campione:
        for u in {v[0] for v in trova_immagini(html, base)}:
            conta[u] = conta.get(u, 0) + 1
    arredo = frozenset(u for u, c in conta.items() if c >= 2) if len(campione) >= 2 else frozenset()
    ris.arredo = len(arredo)
    ris._arredo = arredo
    altre = frozenset(_pulito(q.url) for q in pagine)   # la freccia non salta in un altro capitolo dell'elenco

    def sottocartella(p, n):
        return nome_cartella_pulito(f"{n:0{cifre}d} {p.nome.replace('/', ' ')}") or f"{n:0{cifre}d}"

    # 2) `insieme` pagine alla volta: leggi, scarica, salva. Ogni pagina si salva appena è
    #    pronta: se il giro si ferma a metà, quello fatto resta.
    lock = threading.Lock()
    in_corso: dict = {}      # posto -> ultimo messaggio di quella pagina
    finite = [0]
    # il freno: ogni «troppo in fretta» del sito (429) da quando siamo partiti toglie un capitolo
    # a quelli che vanno insieme, mai sotto uno. Chi è già partito finisce; chi aspetta, aspetta.
    porta = threading.Condition()
    attivi = [0]

    def limite():
        return max(1, insieme - (_RALLENTA[0] - base_rallenta))

    def entra():
        with porta:
            while attivi[0] >= limite() and not stop.is_set():
                porta.wait(0.2)
            attivi[0] += 1

    def esci():
        with porta:
            attivi[0] -= 1
            porta.notify_all()

    def di(i, m=None):
        with lock:
            if m is not None:
                in_corso[i] = m
            if len(in_corso) == 1:
                k, v = next(iter(in_corso.items()))
                testo = f"Page {k} of {N} · {v}"
            elif in_corso:
                testo = f"Pages {', '.join(str(k) for k in sorted(in_corso))} of {N} · {finite[0]} done"
            else:
                testo = f"{finite[0]} of {N} done"
            fatte = finite[0]
        avanzamento(fatte, N, testo)

    def una(i, p):
        """Una pagina con le sue seguenti, nella sua cartella. Torna il suo Risultato."""
        r = Risultato(cartella=dest / sottocartella(p, i), _arredo=arredo, _impronte=ris._impronte)
        entra()
        if stop.is_set():
            esci()
            return r
        di(i, "reading…")
        try:
            letta = lette.pop(id(p), None) or leggi(p)
            if isinstance(letta, GrabError):
                if not stop.is_set():
                    r.pagine_fallite.append(p.nome)
                    r.mancanti.append(Mancante(r.cartella, [p.url], pagina=p))
                return r
            html, base = letta
            av = lambda _f, _t, m: di(i, m)
            scritte, numeri = _giro_pagine(sess, html, base, r.cartella, r, stop, av, paralleli,
                                           "", arredo, evita=altre)
            r.salvate = scritte
            if numeri:
                r.pagine_fatte = 1
            elif not stop.is_set():
                r.pagine_vuote.append(p.nome)
            return r
        finally:
            esci()
            with lock:
                in_corso.pop(i, None)
                finite[0] += 1
            di(i)

    avanzamento(0, N, f"Page 1 of {N} · reading…")
    with ThreadPoolExecutor(max_workers=insieme) as ex:
        futuri = [ex.submit(una, i, p) for i, p in enumerate(scelte, 1)]
    errore = None
    for f in futuri:                       # nell'ordine dell'elenco, non in quello d'arrivo
        try:
            r = f.result()
        except Exception as e:
            errore = errore or e
            continue
        ris.salvate += r.salvate
        ris.trovate += r.trovate
        ris.saltate_piccole += r.saltate_piccole
        ris.doppie += r.doppie
        ris.strisce += r.strisce
        ris.fallite += r.fallite
        ris.pagine_fatte += r.pagine_fatte
        ris.pagine_vuote += r.pagine_vuote
        ris.pagine_fallite += r.pagine_fallite
        ris.mancanti += r.mancanti
        ris.sfogliate += r.sfogliate
        ris.fermata = r.fermata or ris.fermata
    if errore is not None:
        raise errore
    ris.annullato = stop.is_set()
    ris.rallentato = limite() < insieme
    if not ris.annullato and ris.salvate == 0:
        raise GrabError("None of the selected pages had pictures to save.")
    avanzamento(N, N, "Saved")
    return ris


def riprova_mancanti(ris: Risultato, avanzamento=None, stop: threading.Event | None = None) -> int:
    """Il bottone «Try the missing ones again»: riprende solo ciò che manca e mette ogni foto
    al suo posto (il numero lasciato libero). Torna quante foto sono arrivate stavolta;
    ris.mancanti resta con quello che ancora non arriva."""
    stop = stop or threading.Event()
    avanzamento = avanzamento or (lambda *a: None)
    sess = nuova_sessione()
    ancora, arrivate = [], 0
    vecchi = ris.mancanti
    ris.mancanti = []
    for k, m in enumerate(vecchi, 1):
        if stop.is_set():
            ancora.extend(vecchi[k - 1:])
            break
        if m.morta:                                    # link morto sul sito: riprovare non serve
            ancora.append(m)
            continue
        avanzamento(k - 1, len(vecchi), f"Trying again {k} of {len(vecchi)}…")
        if m.pagina is not None:                       # una pagina intera
            try:
                html, base = con_riprova(lambda: leggi_pagina(sess, m.urls[0]), stop)
            except GrabError:
                ancora.append(m)
                continue
            posti = _raccogli(sess, html, base, ris, ris._impronte, stop, lambda *a: None, 6, "", ris._arredo)
            if posti:
                prima = len(ris.mancanti)
                arrivate += _scrivi_cartella(m.cartella, posti, ris, base)
                ancora.extend(ris.mancanti[prima:]); del ris.mancanti[prima:]
                ris.pagine_fatte += 1
            if m.pagina.nome in ris.pagine_fallite:
                ris.pagine_fallite.remove(m.pagina.nome)
            continue
        dati = None                                    # una foto sola
        morte = 0
        for u in m.urls:
            try:
                d = con_riprova(lambda: _scarica(sess, u, m.referer, stop), stop, 2)
            except Exception as e:
                morte += 1 if getattr(e, "morta", False) else 0
                continue
            if estensione(d):
                dati = d
                break
        if dati is None:
            m.morta = bool(m.urls) and morte == len(m.urls) and not stop.is_set()   # ora il sito dice 404/410
            ancora.append(m)
            continue
        imp = hashlib.sha256(dati).hexdigest()
        with _LOCK_IMPRONTE:
            if imp in ris._impronte or troppo_piccola(dati):
                continue                               # era un doppione o un'icona: il posto resta vuoto
            ris._impronte.add(imp)
        m.cartella.mkdir(parents=True, exist_ok=True)
        _scrivi_guadagnato(m.cartella / f"{m.stem}{estensione(dati)}", dati)
        arrivate += 1
    ris.mancanti = ancora
    ris.salvate += arrivate
    # «couldn't be downloaded» ora conta solo ciò che manca ancora
    ris.fallite = [f for f in ris.fallite if any(f[0] == m.urls[0][:200] for m in ancora if m.pagina is None)]
    avanzamento(len(vecchi), len(vecchi), "Saved")
    return arrivate
