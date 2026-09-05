CXX ?= g++
CC ?= clang
CXXFLAGS ?= -O2 -Wall -Wextra -fno-exceptions -fno-rtti -fvisibility=hidden
CFLAGS_JAMES ?= -O2 -include unistd.h -Wno-incompatible-function-pointer-types -Wno-implicit-int -Wno-unused
CXXFLAGS_VIPER ?= -O2 -std=c++17 -DVERSION_NAME='"0.1.0-fused"' -DVERSION_CODE=1
INCLUDES = -Iinclude -Ihost -Isrc -Ithird_party/jamesdsp -DVIPJAM_HOST_BUILD
INC_JAMES = -Ihost -Ithird_party/jamesdsp -Ithird_party/jamesdsp/jdsp
INC_VIPER = -Ithird_party -Ithird_party/viper -Ihost

SRC_CPP = src/VipJamChain.cpp src/viper_bridge.cpp src/host_log.cpp
SRC_C = src/james_bridge.c
SRC = $(SRC_CPP) $(SRC_C)
TEST_SRC = tests/test_params.cpp
OBJDIR = build/host/obj
ENGINELIB = build/host/libvipjam_engines.a

JAMES_SRC = $(shell find third_party/jamesdsp -name "*.c" | grep -v "jamesdsp/jamesdsp.c")
VIPER_SRC = $(shell find third_party/viper -name "*.cpp")
JAMES_OBJ = $(patsubst %.c,$(OBJDIR)/%.o,$(notdir $(JAMES_SRC)))
VIPER_OBJ = $(patsubst %.cpp,$(OBJDIR)/%.o,$(notdir $(VIPER_SRC)))

.PHONY: all test clean engines

all: test_vipjam

$(OBJDIR):
	mkdir -p $(OBJDIR)

engines: $(OBJDIR) $(ENGINELIB)

$(ENGINELIB): $(JAMES_SRC) $(VIPER_SRC)
	@echo "compiling engines (one-time, slow)..."
	@pwd; $(CXX) --version | head -1; ls host/android/log/; echo "INC_VIPER=$(INC_VIPER)"
	@mkdir -p $(OBJDIR)
	@for f in $(JAMES_SRC); do \
	  o="$(OBJDIR)/$$(echo $$f | tr '/.' '__').o"; \
	  if [ ! -f "$$o" ]; then echo "CC $$f"; $(CC) -c $(CFLAGS_JAMES) $(INC_JAMES) "$$f" -o "$$o" || exit 1; fi; \
	done
	@for f in $(VIPER_SRC); do \
	  o="$(OBJDIR)/$$(echo $$f | tr '/.' '__').o"; \
	  if [ ! -f "$$o" ]; then echo "CMD: $(CXX) -c $(CXXFLAGS_VIPER) $(INC_VIPER) $$f"; $(CXX) -c $(CXXFLAGS_VIPER) $(INC_VIPER) "$$f" -o "$$o" || exit 1; fi; \
	done
	ar rcs $@ $(OBJDIR)/*.o

test_vipjam: $(SRC) $(TEST_SRC) $(ENGINELIB)
	$(CC) -c $(CFLAGS_JAMES) $(INCLUDES) -o build/host/james_bridge.o src/james_bridge.c
	$(CXX) $(CXXFLAGS) $(INCLUDES) -Ithird_party -Ithird_party/viper -o $@ $(TEST_SRC) src/VipJamChain.cpp src/viper_bridge.cpp src/host_log.cpp build/host/james_bridge.o $(ENGINELIB) -lm -lpthread

test: test_vipjam
	./test_vipjam

clean:
	rm -f test_vipjam
	rm -rf build
