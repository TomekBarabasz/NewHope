# Lustro golden modelu newhope.fft (FftGolden.scala) w Julii.
#
# Bit w bit to samo co Scala i RTL: BFP z kryterium L1 i normalizacja,
# zaokraglenie polowy w gore, nasycenie na W bitach. Wiazaca definicja
# numeryki jest w Scali (newhope.fft.Bfp); ten plik jej nie ustala, tylko
# odtwarza - przy rozbieznosci racje ma Scala.
#
#   julia FftGolden.jl            # samosprawdzenie wobec fft_golden_vectors.txt
#
# Jako modul:
#   include("FftGolden.jl"); using .FftGolden
#   X, e = rfft_frame(framer(samples, 512, 160)[1])
#
# Tylko biblioteka standardowa (Printf).
#
# Konwencje: liczby zespolone jako krotki (re, im) typu Int64; indeksy
# algorytmu liczone od 0 jak w Scali, dostep do tablic przez +1.

module FftGolden

using Printf

export W, TW, shift_for, shift_round, round_shr, twiddle_table, hann_table,
       core, natural, unpack, framer, rfft_frame, power, magnitude, selfcheck

const W  = 18                      # FftGenerics.dataWidth
const TW = 18                      # FftGenerics.twiddleWidth
const SC = (1 << (TW - 1)) - 1

const Cx = Tuple{Int,Int}

mutable struct Sat
    w::Int
    count::Int
end
Sat(w::Int) = Sat(w, 0)

function (s::Sat)(x::Int)
    hi = (1 << (s.w - 1)) - 1
    lo = -(1 << (s.w - 1))
    if x > hi
        s.count += 1; return hi
    elseif x < lo
        s.count += 1; return lo
    end
    return x
end

round_half_up(x::Float64) = floor(Int, x + 0.5)
round_shr(x::Int, n::Int) = (x + (1 << (n - 1))) >> n        # >> na Int = arytmetyczne
fits(x::Int, w::Int = W)  = -(1 << (w - 1)) <= x < (1 << (w - 1))
l1(c::Cx) = abs(c[1]) + abs(c[2])

"Bfp.shiftRound: ujemne s = przesuniecie w lewo."
function shift_round(x::Int, s::Int)
    s < 0  && return x << -s
    s == 0 && return x
    return (x + (1 << (s - 1))) >> s
end

"Bfp.shiftFor: przesuniecie bloku przed motylkiem (dodatnie = w prawo)."
function shift_for(l::Int, w::Int = W)
    @assert l >= 0
    l == 0 && return 0
    l < (1 << (w - 2))     && return (63 - leading_zeros(l)) - (w - 3)
    l < (1 << (w - 1)) - 2 && return 1
    l < (1 << w) - 4       && return 2
    @assert l < (1 << (w + 1)) - 8 "L1 = $l poza zakresem dla w = $w"
    return 3
end

# Scala liczy tablice przez StrictMath (fdlibm); cos/sin w Julii moga
# roznic sie o 1 ulp, co po zaokragleniu do 17 bitow praktycznie nie
# zmienia wyniku. Wektory testowe to sprawdzaja.
twiddle_table(n::Int, count::Int, scale::Int = SC) =
    [(round_half_up(cos(2pi * k / n) * scale), round_half_up(sin(2pi * k / n) * scale)) for k in 0:count-1]

hann_table(L::Int, scale::Int = SC) =
    [round_half_up((0.5 - 0.5 * cos(2pi * i / L)) * scale) for i in 0:L÷2]

bitrev(i::Int, bits::Int) = Int(bitreverse(UInt64(i)) >> (64 - bits))

"""
FftCore. Zwraca (pamiec w kolejnosci bit-reversed, exp, l1max, nasycenia).
"""
function core(z::Vector{Cx}; exp0::Int = 0, inverse::Bool = false, w::Int = W, tw::Int = TW)
    n = length(z)
    @assert ispow2(n) && n >= 4
    logn = trailing_zeros(n)
    x   = copy(z)
    tab = twiddle_table(n, n ÷ 2, (1 << (tw - 1)) - 1)
    sat = Sat(w)
    l1max = maximum(l1, x)
    e = exp0
    for s in 0:logn-1
        sh = shift_for(l1max, w); e += sh
        p = logn - 1 - s; h = 1 << p; nm = 0
        for i in 0:(n ÷ 2 - 1)
            a = ((i >> p) << (p + 1)) | (i & (h - 1)); b = a | h
            c, sn = tab[((i & (h - 1)) << s) + 1]
            wr, wi = c, (inverse ? sn : -sn)
            ar, ai = shift_round(x[a+1][1], sh), shift_round(x[a+1][2], sh)
            br, bi = shift_round(x[b+1][1], sh), shift_round(x[b+1][2], sh)
            dr, di = ar - br, ai - bi
            @assert fits(dr, w) && fits(di, w) "stopien $s, motylek $i: blad kryterium BFP"
            A = (sat(ar + br), sat(ai + bi))
            B = (sat(round_shr(dr * wr - di * wi, tw - 1)), sat(round_shr(dr * wi + di * wr, tw - 1)))
            x[a+1] = A; x[b+1] = B
            nm = max(nm, l1(A), l1(B))
        end
        l1max = nm
    end
    return x, e, l1max, sat.count
end

function natural(mem::Vector{Cx})
    bits = trailing_zeros(length(mem))
    return [mem[bitrev(k, bits) + 1] for k in 0:length(mem)-1]
end

"""
RealUnpack. zn = Z[k] w porzadku naturalnym. Zwraca (X[0..M], exp, nasycenia).
"""
function unpack(zn::Vector{Cx}, exp::Int, next_shift::Int; w::Int = W, tw::Int = TW)
    m   = length(zn)
    tab = twiddle_table(2m, m + 1, (1 << (tw - 1)) - 1)
    sat = Sat(w); p = next_shift
    out = Vector{Cx}(undef, m + 1)
    for k in 0:m
        kk = k % m
        zkR, zkI = shift_round(zn[kk+1][1], p), shift_round(zn[kk+1][2], p)
        zr = zn[mod(m - kk, m) + 1]
        zrR, zrI = shift_round(zr[1], p), shift_round(zr[2], p)
        sR, sI = zkR + zrR, zkI - zrI          # S = Z[k] + conj(Z[M-k])
        dR, dI = zkR - zrR, zkI + zrI          # D = Z[k] - conj(Z[M-k])
        tR, tI = dI, -dR                       # T = -j D
        @assert all(v -> fits(v, w), (sR, sI, tR, tI)) "k = $k: S/T poza $w bitami"
        c, sn = tab[k+1]; wr, wi = c, -sn
        yR = (sR << (tw - 1)) + tR * wr - tI * wi
        yI = (sI << (tw - 1)) + tR * wi + tI * wr
        out[k+1] = (sat(round_shr(yR, tw)), sat(round_shr(yI, tw)))
    end
    return out, exp + p, sat.count
end

"""
Framer: ramka co `hop` probek, ostatnie L probek (zera przed startem),
okno Hanna, pary (y[2m], y[2m+1]).
"""
function framer(samples::AbstractVector{<:Integer}, L::Int = 512, hop::Int = 160; ww::Int = 18)
    tab  = hann_table(L, (1 << (ww - 1)) - 1)
    ring = zeros(Int, L)
    frames = Vector{Vector{Cx}}()
    for (t0, xs) in enumerate(samples)
        t = t0 - 1
        ring[t % L + 1] = Int(xs)
        if (t + 1) % hop == 0
            y = [round_shr(ring[(t + 1 + i) % L + 1] * tab[(i <= L ÷ 2 ? i : L - i) + 1], ww - 1) for i in 0:L-1]
            push!(frames, [(y[2mm+1], y[2mm+2]) for mm in 0:L÷2-1])
        end
    end
    return frames
end

"Caly tor Rfft dla jednej ramki z framer(). Zwraca (X[0..L/2], exp)."
function rfft_frame(packed::Vector{Cx})
    mem, e, lm, _ = core(packed)
    x, e2, _ = unpack(natural(mem), e, shift_for(lm))
    return x, e2
end

power(x::Vector{Cx}, exp::Int) = ([re * re + im * im for (re, im) in x], 2exp)

function magnitude(p::Int, exp2::Int)
    r = isqrt(p)
    return (p - r * r > r ? r + 1 : r), exp2 >> 1
end

# ---------------------------------------------------------------------
#  Samosprawdzenie
# ---------------------------------------------------------------------

ints(s::AbstractString) = parse.(Int, split(s))
pairs_of(v::Vector{Int}) = [(v[2i-1], v[2i]) for i in 1:length(v)÷2]
function rest(line::AbstractString, key::AbstractString)
    startswith(line, key * " ") || error("oczekiwano '$key', jest: $(first(line, 40))")
    return line[length(key)+2:end]
end

"DFT w Float64 - odniesienie do SNR (O(n^2), wystarczy dla n = 512)."
function dft_real(y::Vector{Int}, bins::Int)
    n = length(y)
    return [sum(y[m+1] * cis(-2pi * ((k * m) % n) / n) for m in 0:n-1) for k in 0:bins-1]
end

function selfcheck(path::AbstractString = joinpath(@__DIR__, "fft_golden_vectors.txt"))
    lines = filter(l -> !isempty(l) && !startswith(l, "#"), readlines(path))
    i = 1; ok = 0
    while i <= length(lines)
        kind, name = split(lines[i])
        if kind == "rfft"
            s   = ints(rest(lines[i+1], "samples"))
            e   = parse(Int, rest(lines[i+2], "exp"))
            exp = pairs_of(ints(rest(lines[i+3], "bins")))
            fr  = framer(s, 512, 512)[1]
            X, ge = rfft_frame(fr)
            @assert ge == e "rfft $name: exp $ge, oczekiwano $e"
            bad = findfirst(k -> X[k] != exp[k], eachindex(exp))
            @assert bad === nothing "rfft $name: prazek $(bad - 1): $(X[bad]) != $(exp[bad])"
            y   = [v for (a, b) in fr for v in (a, b)]
            ref = dft_real(y, 257)
            got = [complex(a, b) * 2.0^ge for (a, b) in X]
            snr = 10log10(sum(abs2, ref) / sum(abs2, got .- ref))
            @printf("  rfft %-14s bit w bit, exp = %3d, SNR wobec Float64 = %5.1f dB\n", name, ge, snr)
        elseif kind == "core"
            z   = pairs_of(ints(rest(lines[i+1], "in")))
            e   = parse(Int, rest(lines[i+2], "exp"))
            exp = pairs_of(ints(rest(lines[i+3], "out")))
            mem, ge, _, _ = core(z; inverse = startswith(name, "inverse"))
            got = natural(mem)
            @assert ge == e "core $name: exp $ge, oczekiwano $e"
            @assert got == exp "core $name: rozbieznosc na $(findfirst(k -> got[k] != exp[k], eachindex(exp)) - 1)"
            @printf("  core %-14s bit w bit, exp = %3d\n", name, ge)
        else
            error("nieznany wpis: $(lines[i])")
        end
        i += 4; ok += 1
    end

    # rogi zakresu: zero nasycen
    sats = 0
    for seed in 1:50
        z = [(rand((-(1 << 17), (1 << 17) - 1)), rand((-(1 << 17), (1 << 17) - 1))) for _ in 1:256]
        mem, e, lm, s1 = core(z)
        _, _, s2 = unpack(natural(mem), e, shift_for(lm))
        sats += s1 + s2
    end
    @assert sats == 0 "nasycenia na rogach zakresu: $sats"

    for p in [collect(0:5000); 1 << 35]
        m, _ = magnitude(p, 0)
        @assert abs(m - sqrt(p)) <= 0.5
    end
    println("OK: $ok zestawow wektorow, rogi bez nasycen, modul zaokragla poprawnie")
end

end # module

if abspath(PROGRAM_FILE) == @__FILE__
    FftGolden.selfcheck()
end
