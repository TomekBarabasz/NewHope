# vertebra-hil, esp32/test: hil_pattern (na wektorach kontraktu) i rdzen
# protokolu hil_cmd na PC.
#   make -C vertebra-hil/esp32/test          buduje i uruchamia
# Ten sam kod co w firmware (components/hil_pattern, hil_cmd_core.c), bez ESP-IDF.

PAT     := ../components/hil_pattern
CMD     := ../components/hil_cmd
VECTORS := ../../contract/i2s/vectors
CFLAGS  := -std=c11 -O2 -Wall -Wextra -Werror -Wconversion -Wno-sign-conversion -I$(PAT)/include -I$(CMD)/include
BUILD   := build

all: run

$(BUILD)/test_hil_pattern: test_hil_pattern.c $(PAT)/hil_pattern.c $(PAT)/hil_vectors.c $(wildcard $(PAT)/include/*.h)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ test_hil_pattern.c $(PAT)/hil_pattern.c $(PAT)/hil_vectors.c

$(BUILD)/test_hil_cmd: test_hil_cmd.c $(CMD)/hil_cmd_core.c $(CMD)/include/hil_cmd.h
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ test_hil_cmd.c $(CMD)/hil_cmd_core.c

run: $(BUILD)/test_hil_pattern $(BUILD)/test_hil_cmd
	./$(BUILD)/test_hil_pattern $(VECTORS)
	./$(BUILD)/test_hil_cmd

clean:
	rm -rf $(BUILD)

.PHONY: all run clean
