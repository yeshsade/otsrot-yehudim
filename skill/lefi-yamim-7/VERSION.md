# Version 7

Separate implementation based on HFNAYA commit `1c132a08eb6e310f336083062fd95d01c4b32ec0`. Version 6 remains unchanged.

Agreed policy: `android-reader-gpt/docs/SKILL7-AGREED-DECISIONS-2026-10-19.md` at repository root. The source of truth for a delivered file is its own `validation.json`, canonical map and measurement report. A successful unit test run alone does not establish a valid complete booklet.

Typography correction, 2026-10-20: one hash-bound profile for the owner-supplied original fonts replaces OS-dependent fallback selection. Font binaries remain local and are supplied separately. The booklet builder accepts --fonts-dir and records a font audit.

Button-policy correction, 2026-10-20: fixed upper-right previous, lower-left next and upper-center return anchors. Removed relocation and side-rotation fallbacks. Proportional shrinking to 75% is allowed only for checkbox collisions. Restored original 29-pixel continuation lettering at 4× raster size. Unresolved placement conflicts produce a report and block delivery; earlier full-booklet validation is not evidence that this corrected policy passes.

Owner clarification, 2026-10-20: the original translucent buttons intentionally overlap page text. Text/heading/ink overlap is allowed for these controls and must not trigger movement, resizing or a failed build. Checkbox and control overlap remains forbidden. Background alpha remains unchanged; additional transparency was suggested as optional, not implemented.

Shulchan Aruch correction, 2026-10-20: restore Skill 6 visual Be'er Hagolah boundaries while retaining Skill 7 measured thirds. Main text, Rema and chapter headings are measured together above the commentary, with gray exclusions. Independent raw-glyph coverage and saved-widget count checks reject silent partial learning coverage. Prior 845 results with 58 Shulchan Aruch boxes are superseded.

Shared Gemara correction, 2026-10-21: logical page aggregation copies occurrence lists instead of aliasing them. Shared-field cloning reads a reopened PDF snapshot to preserve newly inserted navigation links. An integration regression exercises build(), canonical manifests, real saved-file validation and synchronized checkbox state for one amud split across two pages plus another independent amud.
