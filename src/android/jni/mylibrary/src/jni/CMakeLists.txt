cmake_minimum_required(VERSION 3.4.1)
add_library(  ctymp3lame

        SHARED

        src/lame.c
        src/bitstream.c
        src/fft.c
        src/id3tag.c
        src/mpglib_interface.c
        src/presets.c
        src/quantize.c
        src/reservoir.c
        src/tables.c
        src/util.c
        src/VbrTag.c
        src/encoder.c
        src/gain_analysis.c
        src/newmdct.c
        src/psymodel.c
        src/quantize_pvt.c
        src/set_get.c
        src/takehiro.c
        src/vbrquantize.c
        src/version.c
        org_apache_cordova_CTYMediaEditor_Mp3LameWraper.c
        )
include_directories(src/)