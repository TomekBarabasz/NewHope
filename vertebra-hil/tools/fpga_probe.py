import serial, sys, time
from functools import reduce

port = sys.argv[1] if len(sys.argv) > 1 else "COM12"
s = serial.Serial(port, 115200, timeout=0.5, write_timeout=2)
time.sleep(0.2)
print("smieci po otwarciu:", s.read(s.in_waiting).hex(" ") or "-")

def rd(addr, name):
    f = bytes([0xA5, 0x01, addr >> 8, addr & 0xFF])
    f += bytes([reduce(lambda a, b: a ^ b, f)])
    try:
        s.write(f)
    except serial.SerialTimeoutException:
        print("ZAPIS NIE PRZECHODZI: port nie przyjmuje bajtow (PIC / zly port)"); sys.exit(1)
    r = s.read(7)
    print(f"{name:8} {addr:03x}: wyslano {f.hex(' ')}  odebrano {r.hex(' ') or 'NIC'}")

rd(0x000, "magic")    # oczekiwane: 5a 00 48 49 4c 31 26  ("HIL1")
rd(0x001, "proto")    # 5a 00 00 00 00 01 ..
rd(0x002, "ip_id")    # 5a 00 49 32 53 00 ..  ("I2S")
rd(0x003, "build")
rd(0x006, "variant")  # v16_32: 5a 00 00 08 20 10 ..
