import unittest
import xml.etree.ElementTree as ET

from extract_morning_scenario import collect, seconds


class MorningExtractionTest(unittest.TestCase):
    def test_window_boundaries_car_filter_and_terminal_destination(self):
        source = ET.fromstring('''<population>
          <person id="pre"><plan selected="yes"><activity type="home" end_time="06:59:59"/>
            <leg mode="car"/><activity type="work" end_time="17:00:00"/></plan></person>
          <person id="start"><attributes><attribute name="age" class="java.lang.Integer">31</attribute></attributes>
            <plan selected="yes"><activity type="home" facility="a" end_time="07:00:00"/>
            <leg mode="car"/><activity type="secondary" facility="b" start_time="07:15:00" end_time="08:00:00"/>
            <leg mode="car"/><activity type="home" facility="a"/></plan></person>
          <person id="end"><plan selected="yes"><activity type="home" end_time="09:00:00"/>
            <leg mode="car"/><activity type="work"/></plan></person>
          <person id="walk"><plan selected="yes"><activity type="home" end_time="08:00:00"/>
            <leg mode="walk"/><activity type="work"/></plan></person>
        </population>''')
        before = ET.tostring(source)
        cohorts, stats, people, trips = collect(source, seconds("07:00:00"), seconds("09:00:00"), seconds("06:00:00"))
        self.assertEqual(before, ET.tostring(source))
        self.assertEqual(4, people)
        self.assertEqual(5, trips)
        self.assertEqual(2, len(cohorts["core"]))
        self.assertEqual(3, len(cohorts["buffered"]))
        self.assertEqual(1, stats["core"]["multiple_trip_source_persons"])
        self.assertEqual(["start__am_trip_0", "start__am_trip_1"], [p.get("id") for p in cohorts["core"]])
        first = cohorts["core"][0]
        attrs = {a.get("name"): a.text for a in first.find("attributes")}
        self.assertEqual("31", attrs["age"])
        self.assertEqual("07:15:00", attrs["am_source_destination_start_time"])
        self.assertEqual("secondary", attrs["am_destination_activity_type"])
        elements = list(first.find("plan"))
        self.assertEqual(["activity", "leg", "activity"], [e.tag for e in elements])
        self.assertEqual("07:00:00", elements[0].get("end_time"))
        self.assertEqual("b", elements[2].get("facility"))
        self.assertNotIn("start_time", elements[2].attrib)
        self.assertNotIn("end_time", elements[2].attrib)


if __name__ == "__main__":
    unittest.main()
