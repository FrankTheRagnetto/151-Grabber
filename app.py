"""151 Grabber — la finestra (PyQt6).

Link + nome della cartella + dove salvarla → un bottone → la cartella con le
immagini numerate nell'ordine della pagina. Modo «più pagine»: la pagina dà i link,
chi lo usa vede l'elenco (solo l'ultima parte), toglie quelli che non vuole, ogni pagina
tenuta finisce nella sua sottocartella.

Più scarichi insieme: ogni «Download» diventa una scheda sotto il modulo, con la sua
barra; il modulo si svuota subito per il link dopo. Al massimo MAX_LAVORI vanno nello
stesso momento, gli altri aspettano il loro turno.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from pathlib import Path

from PyQt6.QtCore import QObject, Qt, QThread, pyqtSignal
from PyQt6.QtGui import QFontMetrics
from PyQt6.QtWidgets import (QApplication, QButtonGroup, QFileDialog, QFrame, QHBoxLayout,
                             QLabel, QLineEdit, QListWidget, QListWidgetItem,
                             QProgressBar, QPushButton, QRadioButton, QScrollArea,
                             QVBoxLayout, QWidget)

import grabber

APP_ID = "151-grabber"
MAX_LAVORI = 3   # scarichi nello stesso momento; gli altri aspettano


def _cartella_config() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / APP_ID


def leggi_config() -> dict:
    try:
        return json.loads((_cartella_config() / "config.json").read_text())
    except Exception:
        return {}


def scrivi_config(dati: dict) -> None:
    """JSON atomico: temporaneo accanto col PID, poi replace."""
    c = _cartella_config()
    c.mkdir(parents=True, exist_ok=True)
    tmp = c / f"config.json.{os.getpid()}.tmp"
    tmp.write_text(json.dumps(dati, indent=2))
    os.replace(tmp, c / "config.json")


def madre_predefinita() -> Path:
    d = Path.home() / "Downloads"
    return d if d.is_dir() else Path.home()


STILE = """
QWidget { font-size: 11pt; }
QLabel#titolo { font-size: 16pt; font-weight: 600; }
QLabel#aiuto { color: palette(placeholder-text); }
QLineEdit { padding: 6px; }
QPushButton { padding: 6px 14px; }
QPushButton#vai { padding: 10px; font-size: 12pt; font-weight: 600; }
QLabel#esito[stato="errore"] { color: #c0392b; }
QLabel#esito[stato="ok"] { color: #2e7d32; }
QLabel#conta { color: palette(placeholder-text); }
QLabel#esito[stato="attesa"] { color: palette(placeholder-text); }
QLabel#nome_scheda { font-weight: 600; }
QFrame#scheda { border: 1px solid palette(mid); border-radius: 6px; }
QListWidget { padding: 4px; }
QListWidget::item { padding: 4px 2px; }
"""


class Lavoro(QObject):
    """Fa girare una funzione del cuore in un thread; il risultato torna con un segnale."""
    avanza = pyqtSignal(int, int, str)
    finito = pyqtSignal(object)
    errore = pyqtSignal(str)

    def __init__(self, fare):
        super().__init__()
        self.fare = fare                 # fare(avanzamento, stop) -> risultato
        self.stop = threading.Event()

    def _emetti(self, segnale, *a):
        try:
            segnale.emit(*a)
        except RuntimeError:
            pass  # finestra già chiusa

    def run(self):
        try:
            ris = self.fare(lambda f, t, m: self._emetti(self.avanza, f, t, m), self.stop)
            self._emetti(self.finito, ris)
        except grabber.GrabError as e:
            self._emetti(self.errore, str(e))
        except Exception as e:  # mai muto
            self._emetti(self.errore, f"Something went wrong: {e.__class__.__name__}: {e}")


def testo_esito(ris) -> tuple[str, str]:
    """Il riassunto a schermo di un giro finito: (testo, stato)."""
    if ris.annullato and not ris.salvate:
        return "Cancelled: nothing was saved.", ""
    parola = "image" if ris.salvate == 1 else "images"
    if ris.pagine_fatte:
        cart = "folder" if ris.pagine_fatte == 1 else "folders"
        righe = [f"{ris.salvate} {parola} saved in “{ris.cartella.name}”, {ris.pagine_fatte} {cart}."]
        if ris.annullato:
            righe[0] = "Cancelled. " + righe[0]
    else:
        righe = [f"{ris.salvate} {parola} saved in “{ris.cartella.name}”."]
    extra = []
    if ris.sfogliate:
        extra.append(f"followed the site's “next” arrow through {ris.sfogliate} pages")
    if ris.fermata:
        extra.append(ris.fermata)
    if ris.pagine_vuote:
        extra.append(f"no pictures on: {', '.join(ris.pagine_vuote[:5])}" + ("…" if len(ris.pagine_vuote) > 5 else ""))
    if ris.pagine_fallite:
        extra.append(f"{len(ris.pagine_fallite)} pages didn't open: {', '.join(ris.pagine_fallite[:5])}" + ("…" if len(ris.pagine_fallite) > 5 else ""))
    if ris.rallentato:
        extra.append("the site asked to slow down, so fewer chapters ran at once")
    if ris.arredo:
        extra.append(f"{ris.arredo} site pictures (logo, menus) left out")
    if ris.saltate_piccole:
        extra.append(f"{ris.saltate_piccole} tiny icons skipped")
    if ris.doppie:
        extra.append(f"{ris.doppie} duplicates skipped")
    if ris.strisce:
        extra.append(f"{ris.strisce} strips joined to their page")
    foto_mancanti = sum(1 for m in ris.mancanti if m.pagina is None and not m.morta)
    if foto_mancanti:
        extra.append(f"1 picture missing (its number is left free)" if foto_mancanti == 1
                     else f"{foto_mancanti} pictures missing (their numbers are left free)")
    morte = sum(1 for m in ris.mancanti if m.morta)
    if morte:   # il sito stesso le ha tolte (404/410): riprovare non serve
        extra.append(f"1 dead link on the site (the site removed it, nothing to retry)" if morte == 1
                     else f"{morte} dead links on the site (the site removed them, nothing to retry)")
    if extra:
        righe.append(" · ".join(extra))
    return "\n".join(righe), ("errore" if ris.mancanti and not ris.salvate else "ok")


def _metti_esito(lab: QLabel, testo: str, stato: str = ""):
    lab.setText(testo)
    lab.setProperty("stato", stato)
    lab.style().unpolish(lab); lab.style().polish(lab)
    # col word wrap Qt non ricalcola l'altezza e taglia le righe sotto: la diamo noi
    lab.setMinimumHeight(max(0, lab.heightForWidth(max(lab.width(), 300))))


class Scheda(QFrame):
    """Uno scarico: nome, barra, esito, Cancel / Try again / Open folder / Remove.
    Nasce in attesa; la finestra la fa partire quando c'è posto."""
    cambiata = pyqtSignal()

    def __init__(self, titolo: str, dest: Path, fare, parent=None):
        super().__init__(parent)
        self.setObjectName("scheda")
        self.dest, self.fare = dest, fare
        self.stato = "attesa"            # attesa → va → fine
        self.thread: QThread | None = None
        self.lavoro: Lavoro | None = None
        self.ris = None
        self.nome = QLabel(titolo); self.nome.setObjectName("nome_scheda")
        self.barra = QProgressBar(); self.barra.setTextVisible(False); self.barra.setRange(0, 0)
        self.esito = QLabel(""); self.esito.setObjectName("esito"); self.esito.setWordWrap(True)
        self.esito.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.annulla = QPushButton("Cancel"); self.annulla.clicked.connect(self.premi_annulla)
        self.riprova = QPushButton("Try the missing ones again"); self.riprova.hide()
        self.riprova.clicked.connect(self.premi_riprova)
        self.apri = QPushButton("Open folder"); self.apri.hide()
        self.apri.clicked.connect(lambda: subprocess.Popen(["xdg-open", str(self.dest)]))
        self.togli = QPushButton("Remove"); self.togli.hide()
        self.togli.setToolTip("Remove this from the list (the folder stays)")
        self.togli.clicked.connect(self.premi_togli)
        L = QVBoxLayout(self); L.setContentsMargins(10, 8, 10, 8); L.setSpacing(4)
        L.addWidget(self.nome); L.addWidget(self.barra); L.addWidget(self.esito)
        r = QHBoxLayout(); r.addWidget(self.riprova); r.addWidget(self.apri); r.addStretch(1)
        r.addWidget(self.annulla); r.addWidget(self.togli)
        L.addLayout(r)
        _metti_esito(self.esito, "Waiting: another download will finish first…", "attesa")

    def parti(self):
        self._corri(self.fare)

    def _corri(self, fare):
        self.stato = "va"
        self.barra.show(); self.barra.setRange(0, 0)
        self.annulla.show(); self.annulla.setEnabled(True); self.togli.hide()
        self.riprova.hide(); self.apri.hide()
        _metti_esito(self.esito, "Reading the page…")
        self.thread = QThread(self)
        self.lavoro = Lavoro(fare)
        self.lavoro.moveToThread(self.thread)
        self.thread.started.connect(self.lavoro.run)
        self.lavoro.avanza.connect(self._avanza)
        self.lavoro.finito.connect(self._finito)
        self.lavoro.errore.connect(self._errore)
        self.thread.start()
        self.cambiata.emit()

    def _avanza(self, fatte, totale, msg):
        if totale:
            self.barra.setRange(0, totale); self.barra.setValue(fatte)
        _metti_esito(self.esito, msg)

    def _chiudi(self):
        if self.thread:
            self.thread.quit(); self.thread.wait()
        self.thread = self.lavoro = None
        self.stato = "fine"
        self.barra.hide(); self.annulla.hide(); self.togli.show()

    def _finito(self, ris):
        self._chiudi()
        self.ris = ris
        _metti_esito(self.esito, *testo_esito(ris))
        self.apri.setVisible(ris.cartella.exists())
        self.riprova.setVisible(any(not m.morta for m in ris.mancanti))
        self.cambiata.emit()

    def _errore(self, msg):
        self._chiudi()
        _metti_esito(self.esito, msg, "errore")
        self.apri.setVisible(self.dest.is_dir() and any(self.dest.iterdir()))
        self.cambiata.emit()

    def premi_annulla(self):
        if self.stato == "attesa":       # non è ancora partita: via dalla fila
            self.stato = "fine"
            self.barra.hide(); self.annulla.hide(); self.togli.show()
            _metti_esito(self.esito, "Cancelled: nothing was saved.")
            self.cambiata.emit()
        elif self.lavoro is not None:
            self.lavoro.stop.set()
            self.annulla.setEnabled(False)
            _metti_esito(self.esito, "Cancelling…")

    def premi_riprova(self):
        ris = self.ris
        if ris is None or self.lavoro is not None:
            return

        def fare(av, st):
            grabber.riprova_mancanti(ris, av, st)
            ris.annullato = False
            return ris
        self._corri(fare)
        _metti_esito(self.esito, "Trying again…")

    def premi_togli(self):
        if self.stato == "fine":
            self.stato = "tolta"
            self.cambiata.emit()

    def ferma(self, attesa_ms=5000):
        if self.lavoro is not None:
            self.lavoro.stop.set()
        if self.thread:
            self.thread.quit(); self.thread.wait(attesa_ms)


class Finestra(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("151 Grabber")
        self.setMinimumWidth(520)
        self.cfg = leggi_config()
        m = Path(self.cfg.get("madre", "")) if self.cfg.get("madre") else madre_predefinita()
        self.madre = m if m.is_dir() else madre_predefinita()
        self.scarichi: list[Scheda] = []   # gli scarichi a schermo, il più nuovo in cima
        self.thread: QThread | None = None
        self.lavoro: Lavoro | None = None
        self.pagine: list | None = None      # elenco trovato (modo più pagine)
        self.pagine_di: str = ""             # per quale link

        titolo = QLabel("151 Grabber"); titolo.setObjectName("titolo")
        aiuto = QLabel("Paste a link: every image on that page goes into one folder, "
                       "numbered in the order the page shows them.")
        aiuto.setObjectName("aiuto"); aiuto.setWordWrap(True)

        self.link = QLineEdit(); self.link.setPlaceholderText("https://…")
        self.solo = QRadioButton("This page only")
        self.molte = QRadioButton("Pages it links to")
        self.solo.setChecked(True)
        self.modi = QButtonGroup(self); self.modi.addButton(self.solo); self.modi.addButton(self.molte)
        riga_modo = QHBoxLayout(); riga_modo.addWidget(self.solo); riga_modo.addWidget(self.molte); riga_modo.addStretch(1)

        self.nome = QLineEdit(); self.nome.setPlaceholderText("e.g. Rome trip")
        self.dove = QLabel(); self.dove.setMinimumWidth(10)
        self.cambia = QPushButton("Change…")
        self.cambia.clicked.connect(self.scegli_madre)
        riga_dove = QHBoxLayout()
        riga_dove.addWidget(self.dove, 1); riga_dove.addWidget(self.cambia)

        # elenco delle pagine (solo nel modo «più pagine», dopo «Find pages»)
        self.conta = QLabel(""); self.conta.setObjectName("conta")
        self.tutte = QPushButton("All"); self.nessuna = QPushButton("None")
        self.tutte.clicked.connect(lambda: self._spunta_tutte(True))
        self.nessuna.clicked.connect(lambda: self._spunta_tutte(False))
        riga_conta = QHBoxLayout(); riga_conta.addWidget(self.conta, 1)
        riga_conta.addWidget(self.tutte); riga_conta.addWidget(self.nessuna)
        self.elenco = QListWidget(); self.elenco.setMinimumHeight(200)
        self.elenco.itemClicked.connect(self._clic_riga)
        self.elenco.itemChanged.connect(lambda _i: self._aggiorna_conta())
        self.box_elenco = QWidget()
        be = QVBoxLayout(self.box_elenco); be.setContentsMargins(0, 0, 0, 0); be.setSpacing(4)
        be.addLayout(riga_conta); be.addWidget(self.elenco, 1)
        self.box_elenco.hide()

        self.vai = QPushButton("Download images"); self.vai.setObjectName("vai")
        self.vai.clicked.connect(self.premi_vai)
        self.barra = QProgressBar(); self.barra.setTextVisible(False); self.barra.hide()
        self.esito = QLabel(""); self.esito.setObjectName("esito"); self.esito.setWordWrap(True)
        self.esito.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)

        # gli scarichi: una scheda per ognuno, sotto il modulo
        self.box_scarichi = QWidget()
        bs = QVBoxLayout(self.box_scarichi); bs.setContentsMargins(0, 0, 0, 0); bs.setSpacing(4)
        self.conta_scarichi = QLabel("Downloads")
        bs.addWidget(self.conta_scarichi)
        self.dentro = QWidget()
        self.fila = QVBoxLayout(self.dentro); self.fila.setContentsMargins(0, 0, 0, 0); self.fila.setSpacing(6)
        self.fila.addStretch(1)
        self.scorri = QScrollArea(); self.scorri.setWidgetResizable(True)
        self.scorri.setFrameShape(QFrame.Shape.NoFrame); self.scorri.setWidget(self.dentro)
        self.scorri.setMinimumHeight(150)
        bs.addWidget(self.scorri, 1)
        self.box_scarichi.hide()

        L = QVBoxLayout(self)
        L.setContentsMargins(20, 18, 20, 18); L.setSpacing(8)
        L.addWidget(titolo); L.addWidget(aiuto); L.addSpacing(6)
        L.addWidget(QLabel("Link")); L.addWidget(self.link); L.addLayout(riga_modo)
        L.addWidget(QLabel("Folder name")); L.addWidget(self.nome)
        L.addWidget(QLabel("Save inside")); L.addLayout(riga_dove)
        L.addWidget(self.box_elenco, 1)
        L.addSpacing(8); L.addWidget(self.vai); L.addWidget(self.barra)
        L.addWidget(self.esito)
        L.addWidget(self.box_scarichi, 1); L.addStretch(0)

        self.link.returnPressed.connect(self.nome.setFocus)
        self.nome.returnPressed.connect(self.premi_vai)
        self.link.textChanged.connect(lambda _t: self._azzera_elenco())
        self.molte.toggled.connect(lambda _on: self._azzera_elenco())
        self._mostra_dove()

    # -- dove salvare
    def _mostra_dove(self):
        testo = str(self.madre)
        fm = QFontMetrics(self.dove.font())
        larg = max(self.dove.width(), 200)
        self.dove.setText(fm.elidedText(testo, Qt.TextElideMode.ElideMiddle, larg))
        self.dove.setToolTip(testo)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._mostra_dove()

    def scegli_madre(self):
        d = QFileDialog.getExistingDirectory(self, "Save inside", str(self.madre))
        if d:
            self.madre = Path(d)
            self.cfg["madre"] = d
            scrivi_config(self.cfg)
            self._mostra_dove()

    # -- elenco delle pagine
    def _azzera_elenco(self):
        if self.lavoro is not None:
            return
        self.pagine = None
        self.elenco.clear()
        self.box_elenco.hide()
        self._testo_bottone()

    def _testo_bottone(self):
        if self.lavoro is not None:
            self.vai.setText("Cancel")
        elif self.molte.isChecked() and self.pagine is None:
            self.vai.setText("Find pages")
        elif self.molte.isChecked():
            n = sum(1 for i in range(self.elenco.count())
                    if self.elenco.item(i).checkState() == Qt.CheckState.Checked)
            self.vai.setText(f"Download from {n} page{'s' if n != 1 else ''}")
        else:
            self.vai.setText("Download images")

    def _mostra_elenco(self, pagine):
        self.pagine = pagine
        self.pagine_di = self.link.text()
        self.elenco.blockSignals(True)
        self.elenco.clear()
        for p in pagine:
            it = QListWidgetItem(p.nome)
            it.setToolTip(p.url)
            # niente ItemIsUserCheckable: altrimenti un clic sul quadratino la gira due volte
            # (Qt + il nostro clic sulla riga). La spunta la gira solo _clic_riga.
            it.setFlags(Qt.ItemFlag.ItemIsEnabled)
            it.setCheckState(Qt.CheckState.Checked if p.scelta else Qt.CheckState.Unchecked)
            self.elenco.addItem(it)
        self.elenco.blockSignals(False)
        self.box_elenco.show()
        self._aggiorna_conta()

    def _clic_riga(self, it):
        """Un clic su tutta la riga, quadratino compreso, la spunta o la toglie."""
        it.setCheckState(Qt.CheckState.Unchecked if it.checkState() == Qt.CheckState.Checked
                         else Qt.CheckState.Checked)

    def _spunta_tutte(self, si):
        for i in range(self.elenco.count()):
            self.elenco.item(i).setCheckState(Qt.CheckState.Checked if si else Qt.CheckState.Unchecked)

    def _aggiorna_conta(self):
        tot = self.elenco.count()
        n = sum(1 for i in range(tot) if self.elenco.item(i).checkState() == Qt.CheckState.Checked)
        self.conta.setText(f"{tot} pages found · {n} selected")
        if self.pagine:
            for i, p in enumerate(self.pagine):
                p.scelta = self.elenco.item(i).checkState() == Qt.CheckState.Checked
        self._testo_bottone()

    # -- stato a schermo
    def _esito(self, testo, stato=""):
        _metti_esito(self.esito, testo, stato)

    def _occupato(self, si: bool):
        for w in (self.link, self.nome, self.cambia, self.solo, self.molte, self.elenco, self.tutte, self.nessuna):
            w.setEnabled(not si)
        self.barra.setVisible(si)
        self._testo_bottone()

    # -- il giro
    def premi_vai(self):
        if self.lavoro is not None:
            self.lavoro.stop.set()
            self.vai.setEnabled(False)
            self._esito("Cancelling…")
            return
        link, nome, madre = self.link.text(), self.nome.text(), self.madre
        try:
            if not link.strip():
                raise grabber.GrabError("Paste a link.")
            if self.molte.isChecked() and self.pagine is None:
                fare = lambda av, st: grabber.cerca_pagine(link)
            else:
                dest = grabber.cartella_di_arrivo(madre, nome)
                if any(sc.dest == dest and sc.stato in ("attesa", "va") for sc in self.scarichi):
                    raise grabber.GrabError(f"Already downloading into “{dest.name}”: pick another name.")
                if self.molte.isChecked():
                    pagine = [grabber.Pagina(p.url, p.nome, p.scelta) for p in self.pagine]   # copia: il modulo si svuota
                    if not any(p.scelta for p in pagine):
                        raise grabber.GrabError("Tick at least one page.")
                    fare = lambda av, st: grabber.scarica_pagine(pagine, madre, nome, av, st)
                else:
                    fare = lambda av, st: grabber.scarica_tutto(link, madre, nome, av, st)
                self._nuovo_scarico(dest, fare)
                return
        except grabber.GrabError as e:
            self._esito(str(e), "errore")
            return
        self._esito("Looking for pages…")
        self.barra.setRange(0, 0)
        self.thread = QThread(self)
        self.lavoro = Lavoro(fare)
        self._occupato(True)
        self.lavoro.moveToThread(self.thread)
        self.thread.started.connect(self.lavoro.run)
        self.lavoro.avanza.connect(self._avanza)
        self.lavoro.finito.connect(self._trovate)
        self.lavoro.errore.connect(self._errore)
        self.thread.start()

    # -- gli scarichi
    def _nuovo_scarico(self, dest: Path, fare):
        sc = Scheda(dest.name, dest, fare)
        sc.cambiata.connect(self._fila_cambiata)
        self.scarichi.insert(0, sc)
        self.fila.insertWidget(0, sc)
        self.box_scarichi.show()
        # il modulo si svuota: si può incollare subito il link dopo
        self.link.blockSignals(True); self.link.clear(); self.link.blockSignals(False)
        self.nome.clear()
        self._azzera_elenco()
        self._esito(f"“{dest.name}” started. You can paste another link now.", "ok")
        self.link.setFocus()
        self._fila_cambiata()

    def _fila_cambiata(self):
        for sc in [s for s in self.scarichi if s.stato == "tolta"]:
            self.scarichi.remove(sc)
            self.fila.removeWidget(sc); sc.deleteLater()
        # in fila dal più vecchio: chi aspetta da più tempo parte prima
        vanno = sum(1 for s in self.scarichi if s.stato == "va")
        for sc in reversed(self.scarichi):
            if vanno >= MAX_LAVORI:
                break
            if sc.stato == "attesa":
                sc.parti()
                vanno += 1
        attesa = sum(1 for s in self.scarichi if s.stato == "attesa")
        testo = f"Downloads · {vanno} running" if vanno else "Downloads"
        if attesa:
            testo += f" · {attesa} waiting"
        self.conta_scarichi.setText(testo)
        self.box_scarichi.setVisible(bool(self.scarichi))

    def al_lavoro(self) -> bool:
        """Qualcosa gira ancora (la ricerca delle pagine o uno scarico)."""
        return self.lavoro is not None or any(s.stato in ("attesa", "va") for s in self.scarichi)

    def _avanza(self, fatte, totale, msg):
        if totale:
            self.barra.setRange(0, totale); self.barra.setValue(fatte)
        self._esito(msg)

    def _chiudi_lavoro(self):
        if self.thread:
            self.thread.quit(); self.thread.wait()
        self.thread = self.lavoro = None
        self.vai.setEnabled(True)
        self._occupato(False)

    def _trovate(self, pagine):
        self._chiudi_lavoro()
        self._mostra_elenco(pagine)
        self._esito("Click a page to tick or untick it, then press Download.")

    def _errore(self, msg):
        self._chiudi_lavoro()
        self._esito(msg, "errore")

    def closeEvent(self, e):
        if self.lavoro is not None:
            self.lavoro.stop.set()
            if self.thread:
                self.thread.quit(); self.thread.wait(5000)
        for sc in self.scarichi:
            if sc.lavoro is not None:
                sc.lavoro.stop.set()
        for sc in self.scarichi:
            sc.ferma()
        super().closeEvent(e)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("151 Grabber")
    app.setDesktopFileName(APP_ID)
    app.setStyleSheet(STILE)
    w = Finestra()
    if grabber.Image is None:
        w._esito("Pillow is missing: tiny icons won't be filtered out.", "errore")
    w.resize(580, 720)
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
