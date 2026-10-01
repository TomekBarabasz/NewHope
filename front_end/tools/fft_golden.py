"""Lustro golden modelu newhope.fft (src/test/scala/newhope/fft/FftGolden.scala).

Bit w bit to samo co Scala i RTL: BFP z kryterium L1 i normalizacja,
zaokraglenie polowy w gore, nasycenie na W bitach. Do strony PyTorch
w M0 (cechy liczone tak jak na FPGA) i do szybkich eksperymentow.

    python3 tools/fft_golden.py          # samosprawdzenie + tabela SNR

Wymaga numpy tylko w samosprawdzeniu.
"""
import math

W, TW = 18, 18                      # FftGenerics.dataWidth / twiddleWidth
SC = (1 << (TW - 1)) - 1


class Sat:
    def __init__(self, w=W):
        self.count, self.hi, self.lo = 0, (1 << (w - 1)) - 1, -(1 << (w - 1))

    def __call__(self, x):
        if x > self.hi or x < self.lo:
            self.count += 1
            return self.hi if x > self.hi else self.lo
        return x


def round_half_up(x): return math.floor(x + 0.5)
def round_shr(x, n): return (x + (1 << (n - 1))) >> n
def fits(x, w=W): return -(1 << (w - 1)) <= x < (1 << (w - 1))
def l1(c): return abs(c[0]) + abs(c[1])


def shift_round(x, s):
    if s < 0:
        return x << -s
    return x if s == 0 else (x + (1 << (s - 1))) >> s


def shift_for(l, w=W):
    """Bfp.shiftFor: dodatnie = w prawo, ujemne = normalizacja w lewo."""
    if l == 0:
        return 0
    if l < (1 << (w - 2)):
        return l.bit_length() - 1 - (w - 3)
    if l < (1 << (w - 1)) - 2:
        return 1
    if l < (1 << w) - 4:
        return 2
    assert l < (1 << (w + 1)) - 8
    return 3


def twiddle_table(n, count, scale=SC):
    # Scala uzywa StrictMath; math.cos moze sie roznic o 1 ulp, co po
    # zaokragleniu do 17 bitow praktycznie nie zmienia wyniku. Jesli
    # kiedys zmieni - porownaj tablice z FftGenerics, nie obliczenia.
    return [(round_half_up(math.cos(2 * math.pi * k / n) * scale),
             round_half_up(math.sin(2 * math.pi * k / n) * scale)) for k in range(count)]


def hann_table(L, scale=SC):
    return [round_half_up((0.5 - 0.5 * math.cos(2 * math.pi * i / L)) * scale) for i in range(L // 2 + 1)]


def bitrev(i, bits): return int(format(i, f'0{bits}b')[::-1], 2)


def core(z, exp0=0, inverse=False, w=W, tw=TW):
    """FftCore. Zwraca (pamiec w kolejnosci bit-reversed, exp, l1max, nasycenia)."""
    n = len(z); logn = n.bit_length() - 1
    x = [tuple(c) for c in z]
    tab = twiddle_table(n, n // 2, (1 << (tw - 1)) - 1)
    sat = Sat(w)
    l1max, e = max(map(l1, x)), exp0
    for s in range(logn):
        sh = shift_for(l1max, w); e += sh
        p = logn - 1 - s; h = 1 << p; nm = 0
        for i in range(n // 2):
            a = ((i >> p) << (p + 1)) | (i & (h - 1)); b = a | h
            c, sn = tab[(i & (h - 1)) << s]
            wr, wi = c, (sn if inverse else -sn)
            ar, ai = shift_round(x[a][0], sh), shift_round(x[a][1], sh)
            br, bi = shift_round(x[b][0], sh), shift_round(x[b][1], sh)
            dr, di = ar - br, ai - bi
            assert fits(dr, w) and fits(di, w), "blad kryterium BFP"
            A = (sat(ar + br), sat(ai + bi))
            B = (sat(round_shr(dr * wr - di * wi, tw - 1)), sat(round_shr(dr * wi + di * wr, tw - 1)))
            x[a], x[b] = A, B
            nm = max(nm, l1(A), l1(B))
        l1max = nm
    return x, e, l1max, sat.count


def natural(mem):
    b = len(mem).bit_length() - 1
    return [mem[bitrev(k, b)] for k in range(len(mem))]


def unpack(zn, exp, next_shift, w=W, tw=TW):
    """RealUnpack. Zwraca (X[0..M], exp, nasycenia)."""
    m = len(zn); tab = twiddle_table(2 * m, m + 1, (1 << (tw - 1)) - 1)
    sat = Sat(w); p = next_shift; out = []
    for k in range(m + 1):
        kk = k % m
        zkR, zkI = (shift_round(v, p) for v in zn[kk])
        zrR, zrI = (shift_round(v, p) for v in zn[(m - kk) % m])
        sR, sI = zkR + zrR, zkI - zrI
        dR, dI = zkR - zrR, zkI + zrI
        tR, tI = dI, -dR
        assert all(fits(v, w) for v in (sR, sI, tR, tI))
        c, sn = tab[k]; wr, wi = c, -sn
        yR = (sR << (tw - 1)) + tR * wr - tI * wi
        yI = (sI << (tw - 1)) + tR * wi + tI * wr
        out.append((sat(round_shr(yR, tw)), sat(round_shr(yI, tw))))
    return out, exp + p, sat.count


def framer(samples, L=512, hop=160, ww=18):
    """Framer: lista ramek, kazda to L/2 par (y[2m], y[2m+1])."""
    tab = hann_table(L, (1 << (ww - 1)) - 1); ring = [0] * L; frames = []
    for t, x in enumerate(samples):
        ring[t % L] = x
        if (t + 1) % hop == 0:
            y = [round_shr(ring[(t + 1 + i) % L] * tab[i if i <= L // 2 else L - i], ww - 1) for i in range(L)]
            frames.append([(y[2 * m], y[2 * m + 1]) for m in range(L // 2)])
    return frames


def rfft_frame(packed):
    """Caly tor dla jednej ramki z framer(). Zwraca (X[0..L/2], exp)."""
    mem, e, lm, _ = core(packed)
    x, e2, _ = unpack(natural(mem), e, shift_for(lm))
    return x, e2


def power(x, exp): return [re * re + im * im for re, im in x], 2 * exp


def magnitude(p, exp2):
    r = math.isqrt(p)
    return (r + 1 if p - r * r > r else r), exp2 >> 1


# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import numpy as np
    rng = np.random.default_rng(1)

    def speechlike(n, db):
        t = np.arange(n) / 16000; f0 = 140
        s = sum((1 / k) * np.sin(2 * np.pi * f0 * k * t + rng.uniform(0, 6)) for k in range(1, 40) if f0 * k < 7600)
        s = s + 0.05 * rng.standard_normal(n)
        return [int(round(v)) for v in s / np.abs(s).max() * (2 ** 17 - 1) * 10 ** (db / 20)]

    def fixed_half(frame):
        """Wariant z kontraktu v0 (/2 w kazdym stopniu), tylko do porownania."""
        y = [v for pair in frame for v in pair]
        z = [complex(a, b) for a, b in frame]
        n = len(z); logn = n.bit_length() - 1
        x = [(int(c.real), int(c.imag)) for c in z]
        tab = twiddle_table(n, n // 2); sat = Sat()
        for s in range(logn):
            p = logn - 1 - s; h = 1 << p
            for i in range(n // 2):
                a = ((i >> p) << (p + 1)) | (i & (h - 1)); b = a | h
                c, sn = tab[(i & (h - 1)) << s]
                ar, ai, br, bi = (shift_round(v, 1) for v in (*x[a], *x[b]))
                dr, di = ar - br, ai - bi
                x[a] = (sat(ar + br), sat(ai + bi))
                x[b] = (sat(round_shr(dr * c + di * sn, TW - 1)), sat(round_shr(-dr * sn + di * c, TW - 1)))
        X, e2, _ = unpack(natural(x), logn, 1)
        return np.array([complex(*c) for c in X]) * 2.0 ** e2

    print("poziom[dBFS]  SNR BFP+norm  SNR /2 w stopniu")
    for lv in (0, -20, -40, -60, -80):
        fr = framer(speechlike(512, lv), 512, 512)[0]
        y = np.array([v for pair in fr for v in pair], float)
        ref = np.fft.rfft(y)
        X, e = rfft_frame(fr)
        got = np.array([complex(*c) for c in X]) * 2.0 ** e
        snr = lambda g: 10 * np.log10(np.sum(abs(ref) ** 2) / np.sum(abs(g - ref) ** 2))
        print(f"{lv:8d}      {snr(got):8.1f}       {snr(fixed_half(fr)):8.1f}")

    sats = 0
    for i in range(200):
        z = [(int(a), int(b)) for a, b in rng.choice([-2 ** 17, 2 ** 17 - 1], (256, 2))]
        mem, e, lm, s1 = core(z)
        _, _, s2 = unpack(natural(mem), e, shift_for(lm))
        sats += s1 + s2
    print("nasycenia na rogach zakresu (200 blokow):", sats)
    assert sats == 0
    for p in list(range(5000)) + [2 ** 35]:
        m, _ = magnitude(p, 0)
        assert abs(m - math.sqrt(p)) <= 0.5
    print("OK")
