#!/usr/bin/env python3
"""
img2fb.py - konwersja obrazów do formatu framebuffera Mimas V2 i wgrywanie ich
przez UART do demo VGA + LPDDR.

Format docelowy
---------------
Jeden bajt na piksel, RGB332: RRRGGGBB, czerwony na najstarszych bitach.
Wiersze leżą co `stride` bajtów, gdzie stride to szerokość zaokrąglona w górę
do potęgi dwójki (320 -> 512, 640 -> 1024). Dopełnienie nie jest wyświetlane,
ale JEST wysyłane, bo dzięki temu obrazek to jeden ciągły zapis pod jeden
adres, a nie 240 osobnych transakcji.

Pierwszy bajt każdego 16-bajtowego słowa ląduje w najmłodszym bajcie słowa
128-bitowego MCB, czyli piksel o mniejszym x jest bardziej na lewo. Nic tu nie
trzeba przestawiać - strumień bajtów jest dosłownie obrazem w pamięci.

Użycie
------
    ./img2fb.py convert kot.jpg -o kot.fb
    ./img2fb.py send kot.jpg --port /dev/ttyUSB0
    ./img2fb.py send kot.fb  --port COM3 --baud 19200 --buffer 1
    ./img2fb.py pattern -o test.fb --preview test.png
    ./img2fb.py ping --port /dev/ttyUSB0

Wymaga Pillow (konwersja) i pyserial (wysyłka).
"""

import argparse
import struct
import sys
import time

MAGIC = b"\xa5\x5a"
CMD_WRITE = 0x01
CMD_SHOW = 0x02
CMD_PING = 0x03
ACK_OK = 0x4B  # 'K'
ACK_ERR = 0x45  # 'E'

BUFFERS = {0: 0x000000, 1: 0x100000}
WORD = 16  # bajtów na słowo portu MCB

# Bayer 8x8 - rozproszenie błędu kwantyzacji bez pamięci o sąsiadach.
BAYER8 = [
    [0, 32, 8, 40, 2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44, 4, 36, 14, 46, 6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [3, 35, 11, 43, 1, 33, 9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47, 7, 39, 13, 45, 5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
]


# ---------------------------------------------------------------- geometria


def stride_of(width):
    """To samo, co log2Up w SpinalHDL: zaokrąglenie w górę do potęgi dwójki."""
    return 1 << (width - 1).bit_length()


def geometry(args):
    if args.width and args.height:
        w, h = args.width, args.height
    else:
        w, h = 640 // args.scale, 480 // args.scale
    if w % WORD:
        sys.exit(f"szerokość {w} nie dzieli się na słowa po {WORD} B")
    return w, h, stride_of(w)


# ---------------------------------------------------------------- konwersja


def quantize(rgb, w, h, dither=True):
    """RGB (lista krotek, wiersz po wierszu) -> bytearray RGB332."""
    out = bytearray(w * h)
    levels = (7, 7, 3)  # maksymalna wartość kanału: 3, 3 i 2 bity
    shifts = (5, 2, 0)
    for y in range(h):
        row = BAYER8[y & 7]
        base = y * w
        for x in range(w):
            px = rgb[base + x]
            d = (row[x & 7] + 0.5) / 64.0 if dither else 0.5
            byte = 0
            for ch in range(3):
                t = px[ch] * levels[ch] / 255.0
                q = int(t + d)
                if q < 0:
                    q = 0
                elif q > levels[ch]:
                    q = levels[ch]
                byte |= q << shifts[ch]
            out[base + x] = byte
    return out


def pack(pixels, w, h, stride):
    """Wstawia wiersze w odstępie stride, dopełnienie zerami (czarne)."""
    buf = bytearray(stride * h)
    for y in range(h):
        buf[y * stride : y * stride + w] = pixels[y * w : (y + 1) * w]
    return bytes(buf)


def unpack_preview(buf, w, h, stride):
    """Odtwarza to, co realnie zobaczysz na monitorze - do kontroli ditheringu."""
    from PIL import Image

    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            b = buf[y * stride + x]
            r = (b >> 5) & 7
            g = (b >> 2) & 7
            bl = b & 3
            px[x, y] = (r * 255 // 7, g * 255 // 7, bl * 255 // 3)
    return img


def load_image(path, w, h, fit):
    from PIL import Image

    img = Image.open(path).convert("RGB")
    if fit == "stretch":
        img = img.resize((w, h), Image.LANCZOS)
    else:
        sw, sh = img.size
        scale = max(w / sw, h / sh) if fit == "cover" else min(w / sw, h / sh)
        nw, nh = max(1, round(sw * scale)), max(1, round(sh * scale))
        img = img.resize((nw, nh), Image.LANCZOS)
        canvas = Image.new("RGB", (w, h), (0, 0, 0))
        canvas.paste(img, ((w - nw) // 2, (h - nh) // 2))
        img = canvas
    # tobytes zamiast getdata: dziala tak samo w kazdej wersji Pillow
    raw = img.tobytes()
    return [tuple(raw[i:i + 3]) for i in range(0, len(raw), 3)]


def test_pattern(w, h):
    """Wzorzec kontrolny: ramka, przekątne, pasy kolorów, rampa szarości."""
    px = []
    for y in range(h):
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1) or x == y or x + y == w - 1:
                px.append((255, 255, 255))
            elif y < h // 4:
                bar = x * 8 // w
                px.append(((bar & 4) and 255 or 0, (bar & 2) and 255 or 0, (bar & 1) and 255 or 0))
            elif y < h // 2:
                v = x * 255 // (w - 1)
                px.append((v, v, v))
            else:
                px.append((x * 255 // (w - 1), y * 255 // (h - 1), 128))
    return px


# ---------------------------------------------------------------- protokół


class Link:
    def __init__(self, port, baud, timeout=5.0):
        try:
            import serial  # pyserial
        except ImportError:
            sys.exit("brak pyserial - zainstaluj: pip install pyserial")

        self.ser = serial.Serial(port, baud, timeout=timeout)
        # FPGA nie ma resetu po stronie hosta - wyczyść to, co zostało w buforze
        time.sleep(0.05)
        self.ser.reset_input_buffer()

    def _ack(self, what):
        r = self.ser.read(1)
        if not r:
            raise TimeoutError(f"{what}: brak odpowiedzi (sprawdź port, baud i czy MCB się skalibrował)")
        if r[0] == ACK_ERR:
            raise IOError(f"{what}: FPGA odrzuciła pakiet ('E')")
        if r[0] != ACK_OK:
            raise IOError(f"{what}: nieznana odpowiedź 0x{r[0]:02x} - najpewniej rozjechany baud")

    def ping(self):
        self.ser.write(MAGIC + bytes([CMD_PING]))
        self._ack("ping")

    def write(self, addr, payload):
        assert addr % WORD == 0 and len(payload) % WORD == 0
        crc = 0
        for b in payload:
            crc ^= b
        self.ser.write(MAGIC + bytes([CMD_WRITE]) + struct.pack("<II", addr, len(payload)) + payload + bytes([crc]))
        self._ack(f"zapis @0x{addr:06x}")

    def show(self, buf):
        self.ser.write(MAGIC + bytes([CMD_SHOW, buf & 1]))
        self._ack("show")


def send_image(link, data, w, h, stride, base, pad=False):
    """
    Wysyła wiersz po wierszu i DOMYŚLNIE POMIJA DOPEŁNIENIE do stride.

    Dopełnienie nigdy nie trafia na ekran, a przy 320 B linii w odstępie 512 B
    to 37% czasu transmisji. Ceną jest jeden pakiet na wiersz zamiast paczek po
    kilka kilobajtów - narzut nagłówka to 12 B na 320 B danych, czyli wciąż
    wielokrotnie mniej niż dopełnienie.
    """
    span = stride if pad else w
    total = span * h
    sent = 0
    t0 = time.time()
    for y in range(h):
        link.write(base + y * stride, data[y * stride : y * stride + span])
        sent += span
        dt = time.time() - t0
        rate = sent / dt if dt > 0 else 0
        eta = (total - sent) / rate if rate > 0 else 0
        if y % 8 == 0 or y == h - 1:
            print(f"\r  {sent * 100 // total:3d}%  {sent // 1024:4d}/{total // 1024} KiB  "
                  f"{rate / 1024:5.1f} KiB/s  pozostało {eta:4.1f} s", end="", flush=True)
    print()


# ---------------------------------------------------------------- CLI


def add_geometry_args(p):
    p.add_argument("--scale", type=int, default=2, choices=(1, 2),
                   help="2 = framebuffer 320x240 powielany 2x2 (domyślnie), 1 = 640x480")
    p.add_argument("--width", type=int, help="szerokość wprost (zamiast --scale)")
    p.add_argument("--height", type=int, help="wysokość wprost")
    p.add_argument("--fit", default="cover", choices=("cover", "contain", "stretch"))
    p.add_argument("--no-dither", action="store_true", help="bez ditheringu Bayera")
    p.add_argument("--preview", help="zapisz PNG z tym, co realnie zobaczysz")


def add_link_args(p):
    p.add_argument("--port", required=True, help="np. /dev/ttyUSB1 albo COM3")
    p.add_argument("--baud", type=int, default=19200)
    p.add_argument("--pad", action="store_true",
                   help="wyślij też dopełnienie do stride (domyślnie pomijane - i tak go nie widać)")
    p.add_argument("--buffer", type=int, default=1, choices=(0, 1),
                   help="bufor docelowy; domyślnie 1, czyli ten niewyświetlany po starcie")
    p.add_argument("--no-show", action="store_true", help="wgraj, ale nie przełączaj bufora")


def build(args):
    w, h, stride = geometry(args)
    if getattr(args, "source", None) and args.source.lower().endswith((".fb", ".bin", ".raw")):
        data = open(args.source, "rb").read()
        expect = stride * h
        if len(data) != expect:
            sys.exit(f"{args.source} ma {len(data)} B, a geometria {w}x{h} stride {stride} wymaga {expect} B")
        return data, w, h, stride
    px = test_pattern(w, h) if getattr(args, "source", None) is None else load_image(args.source, w, h, args.fit)
    pixels = quantize(px, w, h, dither=not args.no_dither)
    data = pack(pixels, w, h, stride)
    if args.preview:
        unpack_preview(data, w, h, stride).save(args.preview)
        print(f"podgląd: {args.preview}")
    return data, w, h, stride


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("convert", help="obraz -> plik .fb")
    c.add_argument("source")
    c.add_argument("-o", "--out", required=True)
    add_geometry_args(c)

    s = sub.add_parser("send", help="obraz albo .fb -> FPGA")
    s.add_argument("source")
    add_geometry_args(s)
    add_link_args(s)

    p = sub.add_parser("pattern", help="wzorzec kontrolny bez pliku wejściowego")
    p.add_argument("-o", "--out")
    add_geometry_args(p)
    p.add_argument("--port")
    p.add_argument("--baud", type=int, default=19200)
    p.add_argument("--pad", action="store_true")
    p.add_argument("--buffer", type=int, default=1, choices=(0, 1))
    p.add_argument("--no-show", action="store_true")

    q = sub.add_parser("ping", help="sprawdź łącze")
    q.add_argument("--port", required=True)
    q.add_argument("--baud", type=int, default=19200)

    args = ap.parse_args()

    if args.cmd == "ping":
        Link(args.port, args.baud).ping()
        print("odpowiedź 'K' - łącze działa")
        return

    if args.cmd == "pattern":
        args.source = None
    data, w, h, stride = build(args)
    print(f"{w}x{h}, stride {stride} B, razem {len(data) // 1024} KiB")

    out = getattr(args, "out", None)
    if out:
        open(out, "wb").write(data)
        print(f"zapisane: {out}")

    port = getattr(args, "port", None)
    if not port:
        return
    link = Link(port, args.baud)
    link.ping()
    send_image(link, data, w, h, stride, BUFFERS[args.buffer], pad=args.pad)
    if not args.no_show:
        link.show(args.buffer)
        print(f"wyświetlany bufor {args.buffer}")


if __name__ == "__main__":
    main()
