"""Tests for peak_queue_model.py. Run: python -m unittest scripts/mysql-judge-tradeoff/test_peak_queue_model.py"""

import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import peak_queue_model as m  # noqa: E402


class LongestBacklogRunTests(unittest.TestCase):

    def test_ignores_the_first_baseline_and_takes_the_longest_consecutive_run(self):
        starts = [0, 10, 20, 90, 100, 110, 120, 130, 140, 150]
        waits = [5, 5, 5, 0.2, 1.5, 2.0, 0.9, 1.1, 1.2, 1.3]
        run = m.longest_backlog_run(starts, waits)
        self.assertEqual(run["backlogSeconds"], 30)
        self.assertEqual(run["firstBacklogBinStart"], 130)

    def test_threshold_is_strictly_above_one_second_and_empty_bins_break_a_run(self):
        run = m.longest_backlog_run([90, 100, 110], [1.0, None, 1.01])
        self.assertEqual(run["backlogSeconds"], 10)

    def test_a_gap_in_bin_starts_breaks_a_run(self):
        run = m.longest_backlog_run([90, 100, 120], [2, 2, 2])
        self.assertEqual(run["backlogSeconds"], 20)


class FluidModelTests(unittest.TestCase):
    """Hand-derived: B=5, capacity mu = kB, the top segment is 150-180s at 50/s."""

    def test_continuous_duration_matches_the_hand_derivation(self):
        shape = m.Shape(5.0)
        # k=5: Q grows 25/s over 150-180 to 750, stays flat to 240, drains at 20/s; wait > 1s while Q > 25.
        self.assertAlmostEqual(m.fluid(5.0, shape)["continuousBacklogSeconds"], 125.25, delta=0.1)
        # k=7: mu=35, Q peaks at 450, drains at 10/s; Q > 35 from 152.33s to 221.5s.
        self.assertAlmostEqual(m.fluid(7.0, shape)["continuousBacklogSeconds"], 69.17, delta=0.1)
        # k=8: mu=40, Q > 40 from 154s to 197.33s.
        self.assertAlmostEqual(m.fluid(8.0, shape)["continuousBacklogSeconds"], 43.33, delta=0.1)
        # k=9.75: the peak queue is 37.5 against 48.75/s, under a second of wait.
        self.assertEqual(m.fluid(9.75, shape)["continuousBacklogSeconds"], 0.0)

    def test_binned_duration_uses_ten_second_bins(self):
        shape = m.Shape(5.0)
        self.assertEqual(m.fluid(7.0, shape)["backlogSeconds"], 70)
        self.assertEqual(m.fluid(8.0, shape)["backlogSeconds"], 50)


class DiscreteModelTests(unittest.TestCase):

    def test_nominal_k_of_the_experiment_conditions(self):
        self.assertAlmostEqual(m.nominal_k(4, 5.0), 5.42, places=2)
        self.assertAlmostEqual(m.nominal_k(5, 5.0), 6.78, places=2)
        self.assertAlmostEqual(m.nominal_k(6, 5.0), 8.14, places=2)

    def test_outage_engine_without_outage_equals_the_heap_fifo(self):
        shape = m.Shape(5.0)
        rng = np.random.default_rng(7)
        arrivals = m.poisson_arrivals(shape, rng)
        services = m.service_times(len(arrivals), rng)
        heap_starts = m.simulate_fifo(arrivals, services, 5)
        event_starts, ends, redelivered = m.simulate_with_outages(arrivals, services, [3, 2], [])
        np.testing.assert_allclose(heap_starts, event_starts)
        self.assertFalse(redelivered.any())

    def test_a_dead_node_requeues_its_running_work_and_stops_taking_new_work(self):
        arrivals = np.array([0.0, 0.0, 0.5])
        services = np.array([2.0, 2.0, 0.1])
        outage = m.NodeOutage(node=0, down_at=1.0, up_at=10.0, redelivery_delay=0.25)
        starts, ends, redelivered = m.simulate_with_outages(arrivals, services, [1, 1], [outage])
        # Job 0 ran on node 0 and was lost at t=1; node 1 finishes job 1 at 2.0, then takes the
        # re-queued job 0 (head of queue) before job 2.
        self.assertTrue(redelivered[0])
        self.assertAlmostEqual(starts[0], 2.0)
        self.assertAlmostEqual(ends[0], 4.0)
        self.assertAlmostEqual(starts[2], 4.0)

    def test_expected_arrivals_of_the_profile(self):
        self.assertEqual(m.Shape(5.0).expected_arrivals(), 5550.0)
        self.assertEqual(m.Shape(5.0).peak_window(), (90.0, 240.0))


if __name__ == "__main__":
    unittest.main()
