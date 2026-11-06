package com.wbx.mdreader;

/** 全局配色：深/浅两套，编辑器与预览共用同一套语义色。 */
final class Palette {

    final boolean dark;

    final int bg;
    final int surface;
    final int surfaceHi;
    final int line;
    final int text;
    final int text2;
    final int text3;
    final int accent;
    final int chip;
    final int chipActive;
    final int editorBg;
    final int editorText;

    // 语法着色
    final int synHeading;
    final int synCode;
    final int synMarker;
    final int synQuote;
    final int synLink;
    final int synDim;
    final int synCodeBg;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF0F1115;
            surface = 0xFF171A20;
            surfaceHi = 0xFF1E232B;
            line = 0xFF252B34;
            text = 0xFFE9ECF1;
            text2 = 0xFF8D97A6;
            text3 = 0xFF5C6472;
            accent = 0xFF6C8CFF;
            chip = 0xFF191E25;
            chipActive = 0xFF2A3242;
            editorBg = 0xFF11141A;
            editorText = 0xFFDAE0E8;

            synHeading = 0xFF8FB3FF;
            synCode = 0xFF7FD1AE;
            synMarker = 0xFF9A8CFF;
            synQuote = 0xFF7E8A9C;
            synLink = 0xFF6EA8FE;
            synDim = 0xFF6D7684;
            synCodeBg = 0x1A7FD1AE;
        } else {
            bg = 0xFFFFFFFF;
            surface = 0xFFF6F7F9;
            surfaceHi = 0xFFECEEF2;
            line = 0xFFE3E7ED;
            text = 0xFF1B1F24;
            text2 = 0xFF66707C;
            text3 = 0xFF9AA1AA;
            accent = 0xFF2F6FEB;
            chip = 0xFFF2F4F7;
            chipActive = 0xFFE2E8F2;
            editorBg = 0xFFFFFFFF;
            editorText = 0xFF24292F;

            synHeading = 0xFF1F5FD0;
            synCode = 0xFF0A7B5A;
            synMarker = 0xFF6A5AE0;
            synQuote = 0xFF7C8794;
            synLink = 0xFF0969DA;
            synDim = 0xFF8B949E;
            synCodeBg = 0x140A7B5A;
        }
    }

    static Palette of(boolean dark) {
        return new Palette(dark);
    }

    static Palette of(String theme) {
        return new Palette(!"light".equals(theme));
    }
}
