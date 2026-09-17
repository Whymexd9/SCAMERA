#pragma once
#include <elf.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace vivo_neural {
// Read an exported data object without dlopen: vendor constructors must not run
// just to get an embedded model. All addresses are translated through sections.
inline std::vector<uint8_t> extractModel(const uint8_t* bytes, size_t size,
                                        const std::string& name) {
    auto range = [size](uint64_t offset, uint64_t length) {
        if (offset > size || length > size - offset)
            throw std::runtime_error("ELF range outside file");
    };
    range(0, sizeof(Elf64_Ehdr));
    Elf64_Ehdr header;
    std::memcpy(&header, bytes, sizeof(header));
    if (std::memcmp(header.e_ident, ELFMAG, SELFMAG) ||
        header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_machine != EM_AARCH64 ||
        header.e_type != ET_DYN || header.e_shentsize != sizeof(Elf64_Shdr) ||
        !header.e_shnum || header.e_shnum > 4096)
        throw std::runtime_error("Unsupported ELF layout");
    range(header.e_shoff, uint64_t(header.e_shnum) * sizeof(Elf64_Shdr));
    auto section = [&](uint32_t index) {
        if (index >= header.e_shnum) throw std::runtime_error("Invalid section index");
        Elf64_Shdr result;
        std::memcpy(&result, bytes + header.e_shoff + index * sizeof(result), sizeof(result));
        return result;
    };
    for (uint32_t i = 0; i < header.e_shnum; ++i) {
        auto table = section(i);
        if (table.sh_type != SHT_DYNSYM) continue;
        if (table.sh_entsize != sizeof(Elf64_Sym) || table.sh_size % sizeof(Elf64_Sym) ||
            table.sh_size / sizeof(Elf64_Sym) > 100000)
            throw std::runtime_error("Invalid symbol table");
        range(table.sh_offset, table.sh_size);
        auto strings = section(table.sh_link);
        if (strings.sh_type != SHT_STRTAB) throw std::runtime_error("Invalid string table");
        range(strings.sh_offset, strings.sh_size);
        for (uint64_t j = 0; j < table.sh_size; j += sizeof(Elf64_Sym)) {
            Elf64_Sym symbol;
            std::memcpy(&symbol, bytes + table.sh_offset + j, sizeof(symbol));
            if (symbol.st_name >= strings.sh_size) throw std::runtime_error("Invalid symbol name");
            const char* text = reinterpret_cast<const char*>(bytes + strings.sh_offset + symbol.st_name);
            size_t available = strings.sh_size - symbol.st_name;
            if (name.size() >= available || std::memcmp(text, name.data(), name.size()) ||
                text[name.size()] != 0) continue;
            if (ELF64_ST_TYPE(symbol.st_info) != STT_OBJECT ||
                symbol.st_shndx == SHN_UNDEF || symbol.st_shndx >= SHN_LORESERVE ||
                symbol.st_size < 40 || symbol.st_size > 8 * 1024 * 1024)
                throw std::runtime_error("Invalid model symbol");
            auto data = section(symbol.st_shndx);
            if (data.sh_type != SHT_PROGBITS || symbol.st_value < data.sh_addr)
                throw std::runtime_error("Invalid model section");
            uint64_t delta = symbol.st_value - data.sh_addr;
            if (delta > data.sh_size || symbol.st_size > data.sh_size - delta)
                throw std::runtime_error("Model extends beyond section");
            range(data.sh_offset, data.sh_size);
            const uint8_t* start = bytes + data.sh_offset + delta;
            return {start, start + symbol.st_size};
        }
    }
    throw std::runtime_error("Model data symbol not found");
}
} // namespace vivo_neural
