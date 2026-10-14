# Regression cases

## Vayera 843 — actual output and rotated controls

- Previous-week pages: Gemara 157, Mishnah Berurah 158, Likutei Moharan 105,
  Likutei Halakhot 159. Monday Gemara source 6-11 uses `duplicate_amud=b`.
- Tehillim current-week range 97-104 excludes previous-week Likutei Moharan 105.
- Zohar source 83-96 partitions into seven consecutive two-page pairs.
- The initial widget output had 18 real widget/continuation-link intersections
  on rotated pages, despite its old audit reporting none. Link rectangles from
  `get_links()` are in rotated view coordinates; convert by `derotation_matrix`
  before comparing with unrotated widget rectangles, both during placement
  and in the final audit. Never change navigation to accommodate a checkbox.
- Require original white weekday headings to agree with the actual final day
  groups, all 158 learning source pages present, only intro page 1 and blank
  page 160 omitted, and reciprocal links on all four previous-week pages.
- Final audit must fail on an omitted learning page, unexplained duplication,
  stale source/map review, altered navigation, or a widget/link intersection.

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
