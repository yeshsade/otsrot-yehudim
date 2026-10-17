# קורא PDF לאנדרואיד — מפה

קורא PDF חינמי לאנדרואיד, לקהל הרחב, בעברית ובאנגלית. הסימונים נשמרים בתוך הקובץ. האפליקציה מתאימה גם לחוברות אוצרות יהודים.
זו התיקייה של Claude במאגר. התיקייה `android-reader-gpt/` שייכת לסוכן אחר, ואסור לשנות בה דבר.

## באיזה סדר קוראים
| # | מסמך | בשביל מה | מתי מעדכנים |
| --- | --- | --- | --- |
| 1 | [`docs/STATUS.md`](docs/STATUS.md) | איפה עומדים, מה פתוח, **נוהל העבודה**, בדיקות וסביבת הבנייה | בכל גרסה |
| 2 | [`docs/SPEC.md`](docs/SPEC.md) | **המקור הקובע:** מה האפליקציה עושה, מסך אחר מסך | בכל החלטה שמשנה התנהגות |
| 3 | [`docs/DECISIONS.md`](docs/DECISIONS.md) | כל ההחלטות של בעל המוצר, לפי תאריך, במילים שלו | בכל החלטה |
| 4 | [`docs/TEXTS.md`](docs/TEXTS.md) | כל המילים שבמסכים, ומה מהן מאושר | בכל טקסט חדש או אישור |
| 5 | [`docs/TOPICS.md`](docs/TOPICS.md) | **לצד החוברות (GPT):** איך כל נושא עובד — תיבות בחוברת, ותצוגה באפליקציה | בכל החלטה על נושא |

כשמסמכים סותרים זה את זה, `SPEC.md` קובע. מסמכים ישנים נמצאים ב־[`docs/archive/`](docs/archive/README.md): הם היסטוריה, לא הוראות.

## מה נמצא איפה
| תיקייה | מה יש בה |
| --- | --- |
| `android/reader/` | **האפליקציה** (Kotlin). `data/` — לוגיקה ונתונים. `ui/` — המסכים. `src/test` — בדיקות יחידה. `src/androidTest/.../LeanProbe.kt` — בדיקות באמולטור. |
| `android/reader/src/main/assets/settings-copy*.json` | טקסטי מסך ההגדרות המאושרים |
| `android/reader/src/main/res/values*/strings.xml` | שאר הטקסטים (עברית / אנגלית) |
| `android/PdfiumAndroid/`, `android/AndroidPdfViewer/` | מנוע ה־PDF, כולל השינויים שלנו (תיבות וי, סימניות) |
| `android/app/` | הקוד הישן (MJ PDF). לעיון בלבד, לא מפתחים בו |
| `design/` | דוגמאות עיצוב ישנות (HTML) והיסטוריית נוסחי ההגדרות |
| `scripts/` | כלים. בשימוש: `sync_android_settings_copy.py`, `generate_android_fonts.py`. השאר ישנים |
| `native-license-notices/` | הודעות רישוי של ספריות ה־native |

## במסמכי הפרויקט ב־claude.ai
המסמכים הראשיים מועלים לפרויקט "קורא PDF" בתיקייה `claude/`, בשמות זהים. המסמכים הישנים נמצאים שם תחת `claude/archive/`.
`HANDOFF.*` ו־`START_NEW_CHAT.txt` שבשורש הפרויקט שייכים לבעל המוצר.

## רישיון
GPL-3.0, עם ייחוס ל־MJ PDF (Mudlej). מקור הקוד: `docs/archive/UPSTREAM.md`.
