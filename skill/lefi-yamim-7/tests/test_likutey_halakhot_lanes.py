"""Study-side checkboxes must not follow marginal aids to the paper edge."""
from pathlib import Path
import sys
import unittest
import fitz
ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'scripts'), str(ROOT/'days/scripts')]
from placement import Canvas, measured_regions, in_study_lane, box_count


def study_page(doc):
    page = doc.new_page(width=468,height=680)
    for x in (53,245):
        for y in range(70,601,20):page.insert_text((x,y),'M'*20,fontsize=10)
    # Marginal notes occur on both sides and continue below the study.
    for x in (21,438):
        for y in (82,310,630):page.insert_text((x,y),'note',fontsize=5.4)
    # Smaller inline material is still part of the study, not a margin note.
    page.insert_text((300,610),'inline',fontsize=7.3)
    return page


class LikuteyHalakhotLaneTests(unittest.TestCase):
    def test_real_tight_gutter_keeps_six_boxes_without_edge_escape(self):
        # Ink-only extract of the publicly delivered 847 topic page119.
        # Both middle checkpoints need the same narrow gutter around marginalia.
        with fitz.open(ROOT/'tests/fixtures/lh-marginal-notes-tight-gutter.pdf') as doc:
            page=doc[0];canvas=Canvas(page);rects=[]
            for region in measured_regions(page,'likutey_halakhot'):
                for n in range(1,4):
                    rect=canvas.checkbox(region,n/3);rects.append(rect)
                    self.assertTrue(in_study_lane(page,rect,region['rect']))
                    target=region['rect'].y0+region['rect'].height*n/3-9
                    self.assertLessEqual(abs(rect.y0-target),12.01)
            self.assertEqual(len(rects),6)
            for i,r in enumerate(rects):
                self.assertFalse(any(r.intersects(other) for other in rects[i+1:]))

    def test_margin_notes_do_not_define_study_extent(self):
        with fitz.open() as doc:
            page=study_page(doc);regions=measured_regions(page,'likutey_halakhot')
            self.assertEqual(len(regions),2)
            right,left=regions
            self.assertLess(right['rect'].x1,420);self.assertGreater(left['rect'].x0,50)
            self.assertLess(right['rect'].y1,620);self.assertGreater(right['rect'].y1,610)
            self.assertGreater(right['full_rect'].x1,440)
            self.assertEqual([box_count(r['rect'].height,r['full_rect'].height,False) for r in regions],[3,3])

    def test_all_six_boxes_stay_near_study_and_away_from_paper_edges(self):
        with fitz.open() as doc:
            page=study_page(doc);canvas=Canvas(page);rects=[]
            for region in measured_regions(page,'likutey_halakhot'):
                for n in range(1,4):
                    rect=canvas.checkbox(region,n/3);rects.append(rect)
                    self.assertTrue(in_study_lane(page,rect,region['rect']))
                    self.assertGreaterEqual(rect.x0,18)
                    self.assertLessEqual(rect.x1,page.rect.width-18)
            self.assertEqual(len(rects),6)
            for i,r in enumerate(rects):
                self.assertFalse(any(r.intersects(other) for other in rects[i+1:]))

    def test_occupied_outer_margin_uses_clear_gutter(self):
        with fitz.open() as doc:
            page=study_page(doc);region=measured_regions(page,'likutey_halakhot')[0]
            page.draw_rect(fitz.Rect(region['rect'].x1+1,55,450,620),fill=(0,0,0))
            canvas=Canvas(page)
            for n in range(1,4):
                rect=canvas.checkbox(region,n/3)
                self.assertLess(rect.x1,region['rect'].x0)
                self.assertTrue(in_study_lane(page,rect,region['rect']))

    def test_blocked_lanes_stop_instead_of_escaping_to_edge(self):
        with fitz.open() as doc:
            page=doc.new_page(width=468,height=680);r=fitz.Rect(100,80,200,500)
            page.draw_rect(fitz.Rect(70,70,99,510),fill=(0,0,0))
            page.draw_rect(fitz.Rect(201,70,230,510),fill=(0,0,0))
            with self.assertRaisesRegex(ValueError,'bounded study lane'):
                Canvas(page).checkbox({'rect':r,'column':1,'bounded_study_lane':True},2/3)

    def test_edge_drift_is_rejected_even_when_rectangle_fits_page(self):
        with fitz.open() as doc:
            page=doc.new_page(width=468,height=680);r=fitz.Rect(240,60,414,600)
            self.assertFalse(in_study_lane(page,fitz.Rect(452,250,461,259),r))
            self.assertTrue(in_study_lane(page,fitz.Rect(421,250,430,259),r))


if __name__=='__main__':unittest.main()
