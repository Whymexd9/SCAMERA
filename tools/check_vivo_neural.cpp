#include "../app/src/main/cpp/vivo-neural-model.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <cassert>
using namespace vivo_neural;
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)), {});
    assert(!bytes.empty());
    const std::string name = "T2Q_HC_E2E_2x_v1p19_240704_1152_1152_v79_O3_2241_bin";
    auto model = extractModel(bytes.data(), bytes.size(), name);
    assert(model.size() == 805592);
    const uint8_t prefix[] = {0, 0, 0, 2, 0, 0, 0, 3};
    assert(std::memcmp(model.data(), prefix, sizeof(prefix)) == 0);
    auto rejects = [&](const std::vector<uint8_t>& data, const std::string& symbol = "") {
        try { extractModel(data.data(), data.size(), symbol.empty() ? name : symbol); }
        catch (const std::runtime_error&) { return; }
        throw std::runtime_error("Malformed ELF accepted");
    };
    rejects(bytes, "model_does_not_exist");
    for (size_t length : {size_t(0), size_t(63), size_t(4096), bytes.size() - 1}) {
        std::vector<uint8_t> truncated(bytes.begin(), bytes.begin() + length);
        rejects(truncated);
    }
    auto broken = bytes;
    Elf64_Ehdr header;
    std::memcpy(&header, broken.data(), sizeof(header));
    header.e_shoff = UINT64_MAX - 4;
    std::memcpy(broken.data(), &header, sizeof(header));
    rejects(broken);
    broken = bytes;
    broken[EI_CLASS] = ELFCLASS32;
    rejects(broken);
    broken = bytes;
    broken[EI_DATA] = ELFDATA2MSB;
    rejects(broken);
    std::cout << "Vivo model extraction and malformed ELF rejection passed\n";
}
