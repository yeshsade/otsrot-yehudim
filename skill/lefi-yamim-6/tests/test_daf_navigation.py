"""Navigation regressions using generated PDFs, never the user's booklets."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest

import fitz

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "days" / "scripts"))
sys.path.insert(0, str(ROOT / "scripts"))
import add_day_notes as notes
import add_vcheck_boxes as boxes
import apply_manual_answers as answers_script
import build_daf_yomi_test as qa


def fixture_ranges(case):
    if case == "bereshit":
        values = [(2,29),(30,43),(44,57),(58,64),(65,71),(73,79),(80,93),
                  (94,101),(103,109),(111,124),(125,138),(139,145),(146,152),
                  (153,154),(155,159)]
    else:
        values = [(2,33),(34,34),(35,41),(42,48),(49,55),(63,69),(70,76),
                  (77,84),(93,99),(101,107),(108,114),(115,121),(122,128),
                  (129,130),(131,133)]
    return dict(zip(answers_script.TOPICS, values))


def generated_analysis(folder, case):
    """Known regression answers plus explicitly synthetic source page content."""
    ranges = fixture_ranges(case)
    blanks = [34] if case == "sukkot" else []
    headings = {}
    first = 2
    for _slug, day in notes.DAYS:
        count = 6 if case == "sukkot" and day in {"יום רביעי", "שבת קודש"} else 4
        headings[first + 1] = day
        first += count
    for topic in answers_script.TOPICS[2:13]:
        start, end = ranges[topic]
        length = end - start + 1
        for index, (_slug, day) in enumerate(notes.DAYS):
            headings[start + index * (2 if length == 14 else 1)] = day
    source = folder / f"{case}-synthetic-source.pdf"
    font = notes.font_path([r"C:\Windows\Fonts\arial.ttf"])
    with fitz.open() as doc:
        for number in range(1, 161):
            page = doc.new_page(width=468, height=680.25)
            if number not in blanks:
                page.insert_text((70, 120), f"Synthetic source page {number}")
            if number in headings:
                # insert_text places glyphs left-to-right; reversing here gives
                # the engine the same extracted Hebrew order as real sources.
                page.insert_text((70, 180), headings[number][::-1], fontname="fixtureHebrew", fontfile=font)
        doc.save(source, garbage=4, deflate=True)
    previous = ({"משנה ברורה":72, 'ליקוטי מוהר"ן':102, "ליקוטי הלכות":110}
                if case == "bereshit" else
                {"משנה ברורה":62, 'ליקוטי מוהר"ן':92, "ליקוטי הלכות":100})
    base = {"input_pdf": str(source), "total_original_pages":160,
            "topic_ranges": ranges, "blank_pages":blanks,
            "lefi_yamim_6":{"booklet_number":"840" if case == "bereshit" else "838",
                            "parsha_name":case, "date_label":"SYNTHETIC TEST"}}
    answers = {"previous_week_pages":previous, "previous_week_gemara_page":155 if case == "bereshit" else None,
               "no_parsha_this_week":case == "sukkot"}
    if case == "sukkot":
        answers["daf_yomi_duplicate_amud"] = {"יום רביעי":"b", "שבת קודש":"b"}
    analysis = answers_script.apply_days(base, answers)
    return analysis, source


def navigation_doc(analysis, rotation=0):
    count = notes.calculate_base_targets(analysis)["back_cover"] + len(notes.DAYS)
    doc = fitz.open()
    for number in range(count):
        page = doc.new_page(width=468, height=680.25)
        page.insert_text((80, 130), f"Synthetic final page {number + 1}")
        page.set_rotation(rotation)
    return doc


class DafNavigationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temp.name)
        cls.bereshit, cls.source = generated_analysis(cls.folder, "bereshit")
        cls.sukkot, _ = generated_analysis(cls.folder, "sukkot")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def built_navigation(self, analysis, rotation=0):
        with navigation_doc(analysis, rotation) as doc:
            # Ensure an unrelated existing link survives the new navigation.
            doc[0].insert_link({"kind":fitz.LINK_URI, "from":fitz.Rect(70,200,170,220),
                                "uri":"https://example.com/unchanged"})
            summary = notes.add_continuation_links(fitz, doc, analysis)
            pdf = doc.tobytes(garbage=4, deflate=True, deflate_images=True)
        return fitz.open(stream=pdf, filetype="pdf"), summary

    def test_monday_labels_and_real_pdf_targets(self):
        doc, summary = self.built_navigation(self.bereshit)
        with doc:
            records = [r for r in summary["links"] if r["day"] == "יום שני" and r["topic"] == notes.DAF_YOMI_TOPIC]
            expected = {"previous":"לדף היומי-יום ראשון", "next":"לדף היומי-יום שלישי"}
            self.assertEqual({r["type"]:r["label"] for r in records}, expected)
            segments = summary["segments"]
            self.assertEqual(next(r["target"] for r in records if r["type"] == "previous"), segments["sunday"][notes.DAF_YOMI_TOPIC][2])
            self.assertEqual(next(r["target"] for r in records if r["type"] == "next"), segments["tuesday"][notes.DAF_YOMI_TOPIC][1])
            mishnah = [r for r in summary["links"] if r["day"] == "יום שני" and r["topic"] == "משנה"]
            self.assertEqual({r["type"]:r["label"] for r in mishnah},
                             {"previous":"למשניות-יום ראשון", "next":"למשניות-יום שלישי"})
            for record in records:
                matching = [link for link in doc[record["source"]-1].get_links()
                            if link.get("page") == record["target"]-1]
                self.assertEqual(len(matching), 1)
            self.assertEqual(doc[0].get_links()[0]["uri"], "https://example.com/unchanged")

    def test_rotated_positions_no_overlapping_buttons_or_invalid_targets(self):
        for rotation in (0,90,270):
            with self.subTest(rotation=rotation):
                doc, summary = self.built_navigation(self.bereshit, rotation)
                with doc:
                    self.assertEqual(boxes.invalid_internal_links(doc,fitz), [])
                    for page in doc:
                        links = page.get_links()
                        for index, first in enumerate(links):
                            self.assertTrue(page.rect.contains(first["from"]))
                            for second in links[index+1:]:
                                self.assertFalse(first["from"].intersects(second["from"]))
                    for record in summary["links"]:
                        page = doc[record["source"]-1]
                        rect = next(link["from"] for link in page.get_links() if link.get("page") == record["target"]-1)
                        if record["type"] in {"previous","daf-yomi-first-day-previous-week","first-day-previous-week"}:
                            self.assertLessEqual(rect.y1, 45)
                        else:
                            self.assertGreaterEqual(rect.y0, page.rect.height-45)

    def test_sunday_buttons_match_the_user_examples_without_extra_links(self):
        doc, summary = self.built_navigation(self.bereshit)
        with doc:
            daf = [r for r in summary["links"] if r["day"] == "יום ראשון" and
                   r["topic"] == notes.DAF_YOMI_TOPIC and r["type"] != "daf-yomi-previous-week-next"]
            self.assertEqual({r["type"]:r["label"] for r in daf},
                             {"daf-yomi-first-day-previous-week":"לדף היומי-שבוע שעבר",
                              "next":"לדף היומי-יום שני"})
            mishnah = [r for r in summary["links"] if r["day"] == "יום ראשון" and r["topic"] == "משנה"]
            self.assertEqual([(r["type"],r["label"]) for r in mishnah],[("next","למשניות-יום שני")])
            previous = [r for r in summary["links"] if r["type"] == "daf-yomi-first-day-previous-week"]
            self.assertEqual(len(previous),1)
            notes.validate_previous_week_continuation_links(self.bereshit, summary)
            self.assertFalse(any(r["type"] in {"daf-yomi-to-mishnah","daf-yomi-day-previous-week"} for r in summary["links"]))
            self.assertFalse(any(r["type"] == "previous" and r["day"] == "יום ראשון" for r in summary["links"]))
            self.assertFalse(any(r["type"] == "next" and r["day"] == "שבת קודש" for r in summary["links"]))

    def test_all_links_and_page_counts_match_the_original_skill_exactly(self):
        baseline = json.loads((ROOT/"tests/fixtures/original-navigation.json").read_text(encoding="utf-8"))
        for case, analysis in (("sukkot",self.sukkot),("bereshit",self.bereshit)):
            with self.subTest(case=case):
                doc, summary = self.built_navigation(analysis)
                with doc:
                    original = baseline["cases"][case]
                    self.assertEqual(len(doc),original["page_count"])
                    # All original record fields are retained; only label is added.
                    records = [{key:value for key,value in r.items() if key != "label"} for r in summary["links"]]
                    self.assertEqual(records,original["records"])
                    signature = json.loads(json.dumps(boxes.navigation_signature(doc)))
                    self.assertEqual(signature,original["signature"])

    def test_each_existing_button_names_its_own_topic_and_target_day(self):
        doc, summary = self.built_navigation(self.bereshit)
        with doc:
            days = [day for _slug,day in notes.DAYS]
            for record in summary["links"]:
                kind = record["type"]
                if kind in {"first-day-previous-week","daf-yomi-first-day-previous-week"}:
                    target_day = "שבוע שעבר"
                elif kind in {"previous-week-next","daf-yomi-previous-week-next"}:
                    target_day = days[0]
                else:
                    target_day = days[days.index(record["day"]) + (-1 if kind == "previous" else 1)]
                self.assertEqual(record["label"],notes.continuation_label(record["topic"],target_day))

    def test_sukkot_confirmed_duplicates_tehillim_and_reciprocal_links(self):
        analysis = self.sukkot
        for day in ("יום רביעי","שבת קודש"):
            run = notes.daf_yomi_run_lookup(analysis)[day]
            self.assertEqual(len(run["source_pages"]),6)
            self.assertEqual(run["duplicate_amud"],"b")
        teh_range = analysis["topic_ranges"]["תהילים"]
        sunday = [p for p in analysis["day_pages"]["יום ראשון"] if teh_range[0] <= p <= teh_range[1]]
        monday = [p for p in analysis["day_pages"]["יום שני"] if teh_range[0] <= p <= teh_range[1]]
        self.assertTrue(set(sunday) & set(monday), "Shared Tehillim page must not be globally deduplicated")
        doc, summary = self.built_navigation(analysis)
        with doc:
            notes.validate_previous_week_continuation_links(analysis,summary)
            for slug in ("wednesday","shabbos"):
                pages = summary["segments"][slug][notes.DAF_YOMI_TOPIC]
                endpoints = notes.daf_yomi_link_endpoints(dict(notes.DAYS)[slug],pages,notes.daf_yomi_run_lookup(analysis))
                self.assertEqual(endpoints,{"previous_source":pages[1],"next_source":pages[4]})
            self.assertFalse(any("daf-yomi" in r["type"] and "previous-week" in r["type"] for r in summary["links"]))

    def test_duplicate_a_b_both_and_unanswered_are_not_guessed(self):
        for count,amud,last in ((4,None,2),(6,"a",4),(6,"b",4),(8,"both",6)):
            pages = list(range(1,count+1))
            self.assertEqual(notes.daf_yomi_link_endpoints("יום שני",pages,{"יום שני":{"duplicate_amud":amud}}),
                             {"previous_source":pages[1],"next_source":pages[last]})
        with self.assertRaisesRegex(RuntimeError,"Ask whether"):
            notes.daf_yomi_link_endpoints("יום שני",list(range(1,7)),{})

    def test_navigation_survives_actual_checkbox_insertion_and_save(self):
        doc, summary = self.built_navigation(self.bereshit)
        with doc:
            before = boxes.navigation_signature(doc)
            appearances = boxes.make_checkbox_appearances(doc)
            for index,slug in enumerate(("sunday","monday","tuesday")):
                number = summary["segments"][slug]["משנה"][0]
                rect = fitz.Rect(20,300,30,310)
                candidate = boxes.CandidateBox("משנה","mishnah",number,1,1,tuple(rect),rect,f"test_vcheck_{index}")
                boxes.add_checkbox(doc[number-1],fitz,candidate,appearances)
            pdf = doc.tobytes(garbage=4,deflate=True)
        with fitz.open(stream=pdf,filetype="pdf") as result:
            self.assertEqual(boxes.navigation_signature(result),before)
            self.assertEqual(boxes.widget_count(result),3)
            self.assertEqual(boxes.widget_link_intersections(result),[])
            self.assertEqual(boxes.widget_widget_intersections(result),[])
            self.assertEqual(boxes.invalid_internal_links(result,fitz),[])

    def test_qa_builder_preserves_original_pages_and_only_labels_existing_links(self):
        analysis_path = self.folder/"bereshit-analysis.json"
        analysis_path.write_text(json.dumps(self.bereshit,ensure_ascii=False),encoding="utf-8")
        output = self.folder/"qa.pdf"
        previews = self.folder/"qa-previews"
        with contextlib.redirect_stdout(io.StringIO()):
            qa.build_daf_yomi_test(SimpleNamespace(input=str(self.source),analysis=str(analysis_path),duplicate=[],
                                                 previous_week_gemara_page=155,output=str(output),preview_dir=str(previews)))
        report = json.loads((previews/"daf-yomi-test-links.json").read_text())
        monday = [r for r in report["links"] if r["day"] == "יום שני"]
        self.assertEqual({r["label"] for r in monday},{"לדף היומי-יום ראשון","לדף היומי-יום שלישי"})
        with fitz.open(output) as doc:
            self.assertEqual(len(doc),29)
            self.assertEqual(len(report["links"]),14)
            for number in range(1,29):
                self.assertIn(f"Synthetic source page {number + 1}",doc[number-1].get_text())
            self.assertIn("Synthetic source page 155",doc[28].get_text())
            self.assertEqual(boxes.invalid_internal_links(doc,fitz),[])
            self.assertEqual(sum(len(p.get_links()) for p in doc),len(report["links"]))

    def test_bereshit_all_15_original_topic_targets_in_built_pdf(self):
        spec = importlib.util.spec_from_file_location("topics_fixture_builder",ROOT/"topics/scripts/build_topics_booklet.py")
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        starts = [2,30,44,58,65,72,80,94,102,110,125,139,146,153,155]
        info = {"blank_pages":[],"sideways_pages":[],"topic_starts_original":dict(zip(builder.TOPICS,starts))}
        analysis = self.folder/"topics-analysis.json"
        analysis.write_text(json.dumps(info,ensure_ascii=False),encoding="utf-8")
        cover = self.folder/"cover.pdf"
        with fitz.open() as doc:
            for _ in range(4):
                doc.new_page(width=468,height=680.25)
            doc.save(cover)
        output = self.folder/"topics-built.pdf"
        with contextlib.redirect_stdout(io.StringIO()):
            builder.build(SimpleNamespace(interior=str(self.source),cover=str(cover),
                toc=str(ROOT/"topics/assets/toc-15-topics.pdf"),button=str(ROOT/"topics/assets/return-to-toc.png"),
                analysis=str(analysis),output=str(output),whatsapp_url=None,email_url=None,archive_url=None))
        with fitz.open(output) as doc:
            links = doc[1].get_links()
            self.assertEqual([link["page"]+1 for link in links],[start+2 for start in starts])
            self.assertEqual(boxes.invalid_internal_links(doc,fitz),[])


if __name__ == "__main__":
    unittest.main()
