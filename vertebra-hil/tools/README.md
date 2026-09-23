# vertebra-hil: narzędzia i procedura etapu 0

Kryterium etapu 0 (`vertebra-hil.md` §10): `sbt hilFpga/compile hil/compile` przechodzi, `EchoProbe` dostaje echo z obu płytek, a pytania ze schematu z §11 mają odpowiedzi.

## Zmienne środowiska

| Zmienna | Co | Przykład (Linux) |
| --- | --- | --- |
| `VERTEBRA_HIL_FPGA` | UART FPGA na Mimas V2 | `/dev/ttyACM1` |
| `VERTEBRA_HIL_FPGA_PROG` | port programatora Mimas V2 (XMODEM) | `/dev/ttyACM0` |
| `VERTEBRA_HIL_ESP` | natywne USB ESP32-S3 (USB-Serial-JTAG, VID 303a) | `/dev/ttyACM2` |

`EchoProbe` przyjmuje też porty z linii komend: `--fpga_com` i `--esp_com` nadpisują odpowiednio `VERTEBRA_HIL_FPGA` i `VERTEBRA_HIL_ESP`, a w logu widać, skąd wziął się każdy port. Przykład: `sbt "hil/runMain newhope.vertebra.hil.EchoProbe --fpga_com COM5 --esp_com COM7"`.

`sbt "hil/runMain newhope.vertebra.hil.EchoProbe --list"` pokazuje porty z VID:PID. Dwa porty Mimas V2 mają ten sam VID, więc to, który jest którym, sprawdza się raz, ręcznie. Pomaga w tym `screen <port>`: port programatora odpowiada promptem `mimas>` po Enterze, a UART FPGA z wgranym echem po prostu odsyła wpisane znaki. Na Linuksie warto potem przypiąć porty regułą udev po numerze seryjnym, bo numery `ttyACM` zmieniają się po każdym podłączeniu.

## Kontener a sprzęt

Obraz `ghcr.io/spinalhdl/docker` służy do symulacji i generacji Veriloga (kroki 1–2 poniżej). Na Windows kontener działa w maszynie Linuksa Docker Desktop i nie widzi portów `COMx`, więc wszystko, co dotyka sprzętu (`EchoProbe`, `flash_mimas.sh`, później suity `hw_*`), uruchamiamy na hoście: sbt i JDK dla Windows, porty `COM11` itd. `hil` kompiluje się bez Verilatora i GHDL-a, bo Spinal to dla niego tylko biblioteka.

Kontener i host dzielą katalogi `target/`, a zinc zapisuje w nich ścieżki bezwzględne (`/workspace/...` i `C:\...`). Po przejściu z jednego środowiska do drugiego pierwsza kompilacja jest pełna; to koszt czasu, nie błąd.

Da się też przekazać USB do kontenera przez usbipd-win (`usbipd attach --wsl --busid <id> --auto-attach`, potem `docker run --device /dev/ttyACM0 ...`), ale po każdym resecie płytki urządzenie pojawia się na nowo i `--device` przestaje pasować. Na stanowisko HIL tego nie polecamy.

## Kolejność

1. **Symulacja echa**: `sbt "hilFpga/testOnly *HilEchoTopTestplan"`. Musi być zielona, zanim cokolwiek pójdzie na płytkę (§1: harness to też IP).
2. **Verilog**: `sbt "hilFpga/runMain newhope.vertebra.hil.HilEchoTopVerilog"` zapisuje `fpga/hw/gen/HilEchoTop.v`.
3. **ISE (w VM)**: `cd fpga/hw/ise && make`, wynik to `fpga/hw/build/HilEchoTop.bin`. Build pada, jeśli timing nie jest spełniony.
4. **Flash FPGA**: `tools/flash_mimas.sh`. Po wgraniu led[7] miga (heartbeat).
5. **ESP32**: `cd esp32 && idf.py set-target esp32s3 && idf.py build && idf.py -p $VERTEBRA_HIL_ESP flash`.
6. **Echo**: `sbt "hil/runMain newhope.vertebra.hil.EchoProbe"` (albo z `--fpga_com` / `--esp_com`). Obie płytki muszą dać `OK`.

## Firmware PIC na Mimas V2

Firmware [jimmo/numato-mimasv2-pic-firmware](https://github.com/jimmo/numato-mimasv2-pic-firmware) daje UART FPGA na 115200 i osobny port programatora, dzięki czemu SW7 nie jest potrzebny. Wgrywa się go raz, przez `mphidflash` z założoną zworką FWUP (instrukcja w README repozytorium). `programmer.py` z tego samego repozytorium klonujemy do `tools/numato-mimasv2-pic-firmware/` (jest w `.gitignore`) i instalujemy `pip install pyserial xmodem`.

## ISE 14.7 w VM

Spartan-6 wymaga ISE 14.7, bo Vivado go nie obsługuje. Najmniej tarcia daje gotowa maszyna VirtualBox, którą Xilinx wydał jako „ISE 14.7 for Windows 10” (w środku jest Linux z zainstalowanym ISE; importuje się ją też na hoście z Linuksem). Alternatywa to stara dystrybucja Linuksa (np. CentOS 7) z natywnym ISE. Zalecenia:

- Katalog workspace NewHope udostępniony do VM, żeby `make` widział `fpga/hw/gen` bez kopiowania.
- W VM tylko `make`; sbt, flashowanie i EchoProbe zostają na hoście. USB nie musi być przekazywane do VM.
- Licencja WebPACK wystarcza dla XC6SLX9.

Tu zapisujemy wersję obrazu, ścieżkę `settings64.sh` i wszystko, co trzeba było zrobić ręcznie, żeby następna osoba nie musiała tego odkrywać.

## Do odhaczenia w §11 (ze schematu i stanowiska)

- [ ] Oscylator Mimas V2: częstotliwość (zakładamy 100 MHz na V10; UCF i `HilEchoGenerics.clkHz` muszą się zgadzać).
- [ ] VCCO banku 2 (złącze P7) = 3,3 V. Przy okazji bank 1 (P9).
- [ ] Dev board ESP32-S3: model i wolne piny z dala od strappingu (0, 3, 45, 46), USB (19, 20) i pamięci modułu.
- [ ] Logic analyzer: model, maks. częstotliwość próbkowania, czy obsłuży BCLK 6,144 MHz; sigrok go widzi (`sigrok-cli --scan`).

## Gdy echo nie działa

| Objaw | Najpierw sprawdź |
| --- | --- |
| FPGA: zero bajtów, led[7] nie miga | bitstream się nie załadował: power-cycle, ponowny flash |
| FPGA: zero bajtów, led[7] miga, led[4:0] stoi | RX nie dochodzi: zły port (programator zamiast UART) albo zamienione A8/B8 w UCF |
| FPGA: led[4:0] liczy, a bajty nie wracają | TX: B8 w UCF albo baud (fabryczny firmware PIC to 19200) |
| FPGA: led[5] świeci | błędy ramki: baud albo oscylator inny niż 100 MHz |
| ESP32: port jest, echo nie wraca, w odpowiedzi widać logi | konsola wciąż na USB-Serial-JTAG: `sdkconfig.defaults` działa tylko bez istniejącego `sdkconfig`, więc usuń `esp32/sdkconfig` i zbuduj ponownie |
| ESP32: przychodzą śmieci przed echem | log ROM po resecie; EchoProbe je pomija i raportuje jako „smieci przed startem” |
