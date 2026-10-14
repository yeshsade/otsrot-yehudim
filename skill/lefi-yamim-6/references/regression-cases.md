# Regression cases

## Sukkot 838

- No weekly parsha.
- Previous-week pages: Mishnah Berurah 62, Likutei Moharan 92, Likutei Halakhot 100.
- Daf Yomi exceptions on Wednesday and Shabbat use `duplicate_amud=b`.
- Tehillim may repeat the same source page on two different days. Never apply global de-duplication.

## Bereshit 840

- Current-week ranges: Likutei Halakhot 111-124, Sifrei Rabbeinu 125-138,
  Shemot Hatzadikim / Sefer Hamidot 139-145, Likutei Tefillot 146-152,
  Tikun Haklali 153-154, Hashlamot 155-159.
- Previous-week pages: Mishnah Berurah 72, Likutei Moharan 102, Likutei Halakhot 110; Gemara 155.
- Topics TOC targets: `2,30,44,58,65,72,80,94,102,110,125,139,146,153,155`.

Require reciprocal previous-week links, all 15 exact topic targets, preserved
navigation after V insertion, and zero checkbox overlaps.
