# המשך סקיל7 בשיחה חדשה

הענף הפעיל: `gpt/skill7-implementation` במאגר `yeshsade/otsrot-yehudim`. `main` הוא ענף היסטורי של סקיל6; אין להסיק ממנו שסקיל7 או קובצי התמיכה חסרים.

קרא את `SKILL.md` ואת `../../android-reader-gpt/docs/SKILL7-FOR-CLAUDE.md`. ברירת המחדל שסוכמה היא להכין שתי מהדורות, לפי ימים ולפי נושאים, אלא אם המשתמש מבקש אחרת. המשתמש מספק כריכה ופנים. האחרונה שהושלמה היא וישלח847. כל שאלה על עמוד תכלול צילום וקישור להגדלה. המסירה היא קישור נפרד לכל PDF, עם תיעוד בגיטהאב.

## בדיקת קבצים לפני התקנה ובדיקות

משורש המאגר:

```sh
python skill/lefi-yamim-7/scripts/check_font_files.py
```

הבדיקה אינה דורשת חבילות PDF ומציגה במרוכז אילו גופנים חסרים או שונים. אין להריץ57 בדיקות ולקבל שגיאות שרשרת כדי לגלות את אותו חוסר. המצב הנוכחי ב־7.11: שני גופנים מקוריים שמורים במאגר, שניים עדיין חסרים. הפרטים ב־`assets/fonts/README.md`; כל34 קובצי המשתמש נשמרו ב־`assets/font-library/`. אין לטעון שההפקה מוכנה לפני השלמתם, ואין לבקש מהמשתמש שוב קובץ שכבר שמור במאגר.

## התקנה מקומית לסביבת העבודה

```sh
python -m venv .venv-skill7
.venv-skill7/bin/python -m pip install -r skill/lefi-yamim-7/requirements.txt
.venv-skill7/bin/python -c "import sys; sys.path.insert(0, 'skill/lefi-yamim-7/days/scripts'); from typography import audit_profile; print(audit_profile())"
```

ב־Windows הפייתון של הסביבה הוא `.venv-skill7/Scripts/python.exe`. נתיב ברירת המחדל לגופנים נמצא בתוך המאגר, `skill/lefi-yamim-7/assets/fonts`; אין צורך ב־`/workspace/.onboarding/...`, בקובצי שיחה ישנים או בנתיב מהמחשב הקודם. `--fonts-dir` ו־`OTZAROT_FONTS_DIR` הם אפשרויות מפורשות, לא דרישה.

לאחר שהאימות עבר, פעל לפי `SKILL.md`: גילוי המקור, אישורי השלמות וכפילויות, ביקורת תהילים, בנייה מהמקורות, בדיקה בקובץ השמור והבודק העצמאי של Claude. את הבודק והנחיותיו קוראים מענף `claude/android-reader`, כפי שמוסבר בסקיל. כל תמונות הבקרה, תבניות הגבול והקוד נמצאים כבר במאגר. יצירת טיוטת הגדרות סביבה אינה שומרת גופנים שאינם במאגר, ואינה תחליף לבדיקת קבצים אלה.
