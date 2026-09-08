import gzip
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ranking", Path(__file__).parents[1] / "rank_toll_candidates.py")
ranking = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ranking)


class CandidateRankingTest(unittest.TestCase):
    def test_car_entry_bins_and_incomplete_traversals(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            network = root / "network.xml"
            network.write_text('<network><links><link id="a" length="100" freespeed="10" modes="car"/></links></network>')
            events = root / "events.xml.gz"
            data = '''<events>
<event time="90" type="vehicle enters traffic" vehicle="v" networkMode="car"/>
<event time="110" type="entered link" vehicle="v" link="a"/>
<event time="150" type="left link" vehicle="v" link="a"/>
<event time="200" type="entered link" vehicle="v" link="a"/>
<event time="230" type="left link" vehicle="v" link="a"/>
<event time="240" type="entered link" vehicle="v" link="a"/>
<event time="260" type="vehicle leaves traffic" vehicle="v"/>
<event time="100" type="vehicle enters traffic" vehicle="bike" networkMode="bike"/>
<event time="110" type="entered link" vehicle="bike" link="a"/>
<event time="190" type="left link" vehicle="bike" link="a"/>
</events>'''
            with gzip.open(events, "wt") as stream:
                stream.write(data)
            rows = ranking.rank(network, [events], 100, 300, 100)
            self.assertEqual([30.0, 20.0], [row["mean_delay_seconds"] for row in rows])
            self.assertEqual([0, 1], [row["bin"] for row in rows])
            self.assertEqual([1, 1], [row["traversals"] for row in rows])
            self.assertEqual(1, len(ranking.rank(network, [events], 100, 200, 100)))

    @unittest.skipUnless(importlib.util.find_spec("zstandard"), "optional zstandard dependency not installed")
    def test_zstd_event_stream(self):
        import zstandard
        with tempfile.TemporaryDirectory() as folder:
            events = Path(folder) / "events.xml.zst"
            with zstandard.open(events, "wb") as stream:
                stream.write(b'<events><event type="entered link" link="a" time="100"/></events>')
            self.assertEqual("a", list(ranking.xml_elements(events, "event"))[0]["link"])

    def test_rejects_own_toll_events(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            network, events = root / "network.xml", root / "events.xml"
            network.write_text('<network><links/></network>')
            events.write_text('<events><event type="personMoney" purpose="congestionPricing" amount="-2"/></events>')
            with self.assertRaisesRegex(ValueError, "not a no-toll baseline"):
                ranking.rank(network, [events], 100, 300, 100)


if __name__ == "__main__":
    unittest.main()
