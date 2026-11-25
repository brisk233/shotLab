"""PC 复算器：与 Android TrackletEngine 使用同一套轨迹和命中规则。"""
import json
import math
import os
import sys
from bisect import bisect_left

SRC = sys.argv[1]
RIM = tuple(float(x) for x in sys.argv[2].split(","))
data = json.load(open(SRC, encoding="utf-8"))
W, H = data["w"], data["h"]
RIM_TRACK = [
    (
        point["tMs"] / 1000.0,
        tuple(point["rim"]),
        float(point.get("confidence", 0.0)),
        int(point.get("segment", 0)),
    )
    for point in data.get("rimTrack", [])
]
rim_cx = (RIM[0] + RIM[2]) / 2
rim_cy = (RIM[1] + RIM[3]) / 2
rim_w = RIM[2] - RIM[0]
TOL = float(sys.argv[3]) if len(sys.argv) > 3 else 0.55
ALLOW_LOW_RES_MADE = (
    sys.argv[4].lower() not in ("0", "false") if len(sys.argv) > 4 else True
)
ALLOW_RIM_REFINEMENT = (
    sys.argv[5].lower() not in ("0", "false") if len(sys.argv) > 5 else True
)

TRACK_GATE = 0.08
TRACK_GATE_PER_SECOND = 0.12
MAX_TRACK_GATE = 0.20
MAX_TRACK_GAP_SECONDS = 0.60
MAX_MISSED_FRAMES = 2
MIN_RIM_REFINE_TRACKS = 4
MIN_RELIABLE_RIM_PX = 60.0
MIN_ADAPTIVE_ROUGH_RIM_PX = 30.0
LOW_RES_TOLERANCE_MAX_FRAME_W = 720
LOCAL_RIM_WINDOW_MS = 8.0
MAX_OBSERVED_CROSS_GAP_SECONDS = 0.8
MAX_IMPACT_APPROACH_SECONDS = 1.4
MAX_IMPACT_EXTRAPOLATION_STEPS = 30
IMPACT_STEP_SECONDS = 0.02
MAX_LOCAL_IMPACT_ERROR_WIDTHS = 0.65
OUTBOUND_START_RIM_WIDTHS = 4.0
MIN_SHOT_RISE = 0.03


def center(box):
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


def nearest_person(point, frame):
    people = frame.get("persons") or ([frame["person"]] if frame.get("person") else [])
    if not people:
        return None
    x, y = point
    return min(
        people,
        key=lambda box: (x - (box[0] + box[2]) / 2) ** 2 + (y - (box[1] + box[3]) / 2) ** 2,
    )


def inside_person(point, frame):
    box = nearest_person(point, frame)
    if box is None:
        return False
    x, y = point
    pad_x = (box[2] - box[0]) * 0.15
    bottom_pad = (box[3] - box[1]) * 0.10
    return box[0] - pad_x <= x <= box[2] + pad_x and box[1] <= y <= box[3] + bottom_pad


def downward_cross_at(points, apex, y):
    for idx in range(max(1, apex + 1), len(points)):
        previous, current = points[idx - 1], points[idx]
        if current[0] - previous[0] > 0.8:
            continue
        if previous[2] >= y or current[2] < y:
            continue
        span = current[2] - previous[2]
        if abs(span) < 1e-6:
            continue
        fraction = min(1.0, max(0.0, (y - previous[2]) / span))
        return previous[1] + (current[1] - previous[1]) * fraction
    return None


def densest_cluster(values, width):
    if not values:
        return 0, 0.0
    ordered = sorted(values)
    start = 0
    support = 0
    center_x = 0.0
    for end in range(len(ordered)):
        while ordered[end] - ordered[start] > width:
            start += 1
        count = end - start + 1
        if count > support:
            support = count
            center_x = (ordered[start] + ordered[end]) / 2
    return support, center_x


def estimate_shot_type(release, rim):
    rim_cx = (rim[0] + rim[2]) / 2
    rim_cy = (rim[1] + rim[3]) / 2
    rim_w = rim[2] - rim[0]
    ratio = math.hypot(
        (release[1] - rim_cx) * W,
        (release[2] - rim_cy) * H,
    ) / (rim_w * W)
    if ratio <= 2.0:
        return "LAYUP"
    if ratio >= 4.8:
        return "THREE"
    return "TWO"


def refine_rim_from_tracks(tracks, rough):
    candidates = []
    for points in tracks:
        if len(points) < 5 or points[-1][0] - points[0][0] < 0.30:
            continue
        apex = min(range(len(points)), key=lambda idx: points[idx][2])
        if points[apex][2] >= (rough[1] + rough[3]) / 2:
            continue
        candidates.append((points, apex))
    if len(candidates) < MIN_RIM_REFINE_TRACKS:
        return None

    rough_w = rough[2] - rough[0]
    rough_cx = (rough[0] + rough[2]) / 2
    rough_cy = (rough[1] + rough[3]) / 2
    near_min_y = max(0.0, rough_cy - 2 * rough_w)
    min_y = max(0.0, rough_cy - max(6 * rough_w, 0.30))
    max_y = min(1.0, rough_cy + 0.5 * rough_w)
    step = 1.0 / H
    cluster_width = rough_w * 1.2
    min_support = max(MIN_RIM_REFINE_TRACKS, round(len(candidates) * 0.45))
    best_support = 0
    best_x = 0.0
    best_y = 0.0
    y = min_y
    while y <= max_y:
        crossings = [
            cross_x
            for points, apex in candidates
            if (cross_x := downward_cross_at(points, apex, y)) is not None
        ]
        support, center_x = densest_cluster(crossings, cluster_width)
        if support > best_support:
            best_support = support
            best_x = center_x
            best_y = y
        y += step
    if best_support < min_support:
        return None

    rough_crossings = [
        cross_x
        for points, apex in candidates
        if (cross_x := downward_cross_at(points, apex, rough_cy)) is not None
    ]
    rough_support, rough_center = densest_cluster(rough_crossings, cluster_width)
    if os.environ.get("SHOTLAB_DEBUG_RIM") == "1":
        print(
            "RIM_DEBUG "
            f"candidates={len(candidates)} min_y={min_y:.4f} near_min_y={near_min_y:.4f} "
            f"best=({best_x:.4f},{best_y:.4f}) support={best_support} "
            f"rough_support={rough_support} rough_center={rough_center:.4f}",
            file=sys.stderr,
        )
    gross_upward_shift = best_y < near_min_y - 2 * step
    if gross_upward_shift and rough_support > 1:
        return None
    if best_y <= min_y + 2 * step or best_y >= max_y - 2 * step:
        return None

    best_center_error = abs(best_x - rough_cx) / rough_w
    rough_center_error = abs(rough_center - rough_cx) / rough_w
    support_improved = best_support >= rough_support + 3
    center_improved = best_support >= rough_support and rough_center_error - best_center_error >= 0.5
    if not support_improved and not center_improved:
        return None

    shift_x = (best_x - rough_cx) * W
    shift_y = (best_y - rough_cy) * H
    shift_px = round(math.hypot(shift_x, shift_y))
    if shift_px < max(4, rough_w * W * 0.35):
        return None
    refined = (
        max(0.0, best_x - rough_w / 2),
        max(0.0, best_y - (rough[3] - rough[1]) / 2),
        min(1.0, best_x + rough_w / 2),
        min(1.0, best_y + (rough[3] - rough[1]) / 2),
    )
    return refined, shift_px


def adapt_local_rim(tracks, segment, center_time, base):
    base_w = base[2] - base[0]
    base_h = base[3] - base[1]
    base_cx = (base[0] + base[2]) / 2
    base_cy = (base[1] + base[3]) / 2
    local = []
    for points in tracks:
        if points[0][4] != segment:
            continue
        if len(points) < 5 or points[-1][0] - points[0][0] < 0.30:
            continue
        track_center = (points[0][0] + points[-1][0]) / 2
        if abs(track_center - center_time) <= LOCAL_RIM_WINDOW_MS:
            local.append(points)
    if len(local) < 3:
        return base

    min_y = max(0.0, base_cy - 1.5 * base_w)
    max_y = min(1.0, base_cy + 1.5 * base_w)
    step = 1.0 / H
    cluster_width = base_w * 1.2
    support = 0
    center_x = base_cx
    center_y = base_cy
    y = min_y
    while y <= max_y:
        crosses = []
        for points in local:
            apex = min(range(len(points)), key=lambda idx: points[idx][2])
            if points[apex][2] >= y:
                continue
            cross_x = downward_cross_at(points, apex, y)
            if cross_x is not None:
                crosses.append(cross_x)
        count, candidate_x = densest_cluster(crosses, cluster_width)
        score = count - abs(y - base_cy) / base_w * 0.02
        current_score = support - abs(center_y - base_cy) / base_w * 0.02
        if score > current_score:
            support = count
            center_x = candidate_x
            center_y = y
        y += step
    if support < 3:
        return base
    if abs(center_x - base_cx) < base_w * 0.25 and abs(center_y - base_cy) < base_w * 0.25:
        return base
    return (
        max(0.0, center_x - base_w / 2),
        max(0.0, center_y - base_h / 2),
        min(1.0, center_x + base_w / 2),
        min(1.0, center_y + base_h / 2),
    )


tracklets = []
active = []
for frame in data["frames"]:
    t = frame["t"]
    segment = int(frame.get("segment", 0))
    next_active = []
    used = set()
    for idx, missed in active:
        track = tracklets[idx]
        last_t, last_x, last_y, _, last_segment = track[-1]
        if last_segment != segment:
            continue
        dt = max(1e-3, t - last_t)
        if dt > MAX_TRACK_GAP_SECONDS:
            continue
        if len(track) >= 2:
            prev_t, prev_x, prev_y, _, _ = track[-2]
            observed_dt = max(1e-3, last_t - prev_t)
            expected_x = last_x + (last_x - prev_x) * dt / observed_dt
            expected_y = last_y + (last_y - prev_y) * dt / observed_dt
        else:
            expected_x, expected_y = last_x, last_y
        gate = min(MAX_TRACK_GATE, TRACK_GATE + TRACK_GATE_PER_SECOND * dt)
        best = None
        best_d = float("inf")
        for candidate_idx, candidate in enumerate(frame["cands"]):
            if candidate_idx in used:
                continue
            x, y = center(candidate)
            distance = math.hypot(x - expected_x, y - expected_y)
            if distance < gate and distance < best_d:
                best_d = distance
                best = (candidate_idx, x, y, candidate[4])
        if best is not None:
            candidate_idx, x, y, confidence = best
            if inside_person((x, y), frame):
                continue
            used.add(candidate_idx)
            track.append((t, x, y, confidence, segment))
            next_active.append((idx, 0))
        elif missed < MAX_MISSED_FRAMES:
            next_active.append((idx, missed + 1))
    for candidate_idx, candidate in enumerate(frame["cands"]):
        if candidate_idx in used:
            continue
        x, y = center(candidate)
        if inside_person((x, y), frame):
            continue
        tracklets.append([(t, x, y, candidate[4], segment)])
        next_active.append((len(tracklets) - 1, 0))
    active = next_active


trusted_rim_track = [point for point in RIM_TRACK if point[2] >= 0.28]
trusted_rim_times = [point[0] for point in trusted_rim_track]
has_dynamic_rim = len(trusted_rim_track) > 0
has_scene_cut = any(int(frame.get("segment", 0)) > 0 for frame in data["frames"])
refined_rim = (
    None
    if has_scene_cut or not ALLOW_RIM_REFINEMENT
    else refine_rim_from_tracks(tracklets, RIM)
)
engine_rim = refined_rim[0] if refined_rim is not None else RIM
if has_dynamic_rim:
    rim_adjusted_px = round(
        math.hypot(
            (trusted_rim_track[-1][1][0] + trusted_rim_track[-1][1][2] - rim_cx * 2) / 2 * W,
            (trusted_rim_track[-1][1][1] + trusted_rim_track[-1][1][3] - rim_cy * 2) / 2 * H,
        )
    )
elif refined_rim is not None:
    rim_adjusted_px = refined_rim[1]
else:
    rim_adjusted_px = 0
rough_rim_low_res = rim_w * W < MIN_RELIABLE_RIM_PX
relaxed_rough_rim_tolerance = (
    rough_rim_low_res
    and W <= LOW_RES_TOLERANCE_MAX_FRAME_W
    and rim_w * W >= MIN_ADAPTIVE_ROUGH_RIM_PX
)
low_res_corrected = refined_rim is not None and rough_rim_low_res


def fit_quad(points):
    t0 = points[0][0]
    sums = [0.0] * 5
    weighted_y = [0.0] * 3
    for t, _, y, _, _ in points:
        dt = t - t0
        power = 1.0
        for idx in range(5):
            sums[idx] += power
            power *= dt
        for idx in range(3):
            weighted_y[idx] += dt**idx * y
    matrix = [
        [sums[4], sums[3], sums[2], weighted_y[2]],
        [sums[3], sums[2], sums[1], weighted_y[1]],
        [sums[2], sums[1], sums[0], weighted_y[0]],
    ]
    for col in range(3):
        pivot = max(range(col, 3), key=lambda row: abs(matrix[row][col]))
        if abs(matrix[pivot][col]) < 1e-12:
            return None
        if pivot != col:
            matrix[col], matrix[pivot] = matrix[pivot], matrix[col]
        divisor = matrix[col][col]
        for idx in range(col, 4):
            matrix[col][idx] /= divisor
        for row in range(3):
            if row == col:
                continue
            factor = matrix[row][col]
            for idx in range(col, 4):
                matrix[row][idx] -= factor * matrix[col][idx]
    return matrix[0][3], matrix[1][3], matrix[2][3], t0


def rim_at_time(track, time_s, times=None):
    if not track:
        return None
    if times is None:
        times = [point[0] for point in track]
    index = bisect_left(times, time_s)
    before = track[index - 1] if index > 0 else None
    after = track[index] if index < len(track) else None
    nearest_age = min(
        abs(time_s - before[0]) if before is not None else float("inf"),
        abs(after[0] - time_s) if after is not None else float("inf"),
    )
    if nearest_age > 1.0:
        return None
    if before is None:
        return after[1]
    if after is None or after[0] == before[0]:
        return before[1]
    if after[0] - before[0] > 1.0:
        return before[1] if abs(time_s - before[0]) <= abs(after[0] - time_s) else after[1]
    fraction = min(1.0, max(0.0, (time_s - before[0]) / (after[0] - before[0])))
    a, b = before[1], after[1]
    return tuple(a[i] + (b[i] - a[i]) * fraction for i in range(4))


def predicted_hit_time(
    first_time,
    a,
    b,
    c,
    apex_time,
    fallback_cy,
    rim_track,
    rim_times,
    descending_only,
):
    if not rim_track:
        discriminant = b * b - 4 * a * (c - fallback_cy)
        if discriminant < 0:
            return None
        root = math.sqrt(discriminant)
        roots = [(-b + root) / (2 * a), (-b - root) / (2 * a)]
        if descending_only:
            return min((value for value in roots if value > 0 and value >= apex_time), default=None)
        return min((value for value in roots if value > 0), default=None)

    start = max(0.0, apex_time)
    end = 3.5
    boundaries = {start, end}
    left_index = max(0, bisect_left(rim_times, first_time + start) - 1)
    right_index = min(len(rim_times), bisect_left(rim_times, first_time + end) + 1)
    for index in range(left_index, right_index):
        for boundary in (
            rim_times[index] - 1.0,
            rim_times[index],
            rim_times[index] + 1.0,
        ):
            local = boundary - first_time
            if start < local < end:
                boundaries.add(local)
    for index in range(max(0, left_index), min(len(rim_times) - 1, right_index)):
        gap = rim_times[index + 1] - rim_times[index]
        if gap > 1.0:
            switch = rim_times[index] + gap / 2.0 - first_time
            if start < switch < end:
                boundaries.add(switch)
    boundaries = sorted(boundaries)
    best = None
    for left, right in zip(boundaries, boundaries[1:]):
        if right <= left:
            continue
        mid_rim = rim_at_time(rim_track, first_time + (left + right) / 2.0, rim_times)
        if mid_rim is None:
            rim0_cy = fallback_cy
            rim1_cy = fallback_cy
        else:
            rim0 = rim_at_time(rim_track, first_time + left, rim_times)
            rim1 = rim_at_time(rim_track, first_time + right, rim_times)
            rim0_cy = (rim0[1] + rim0[3]) / 2.0 if rim0 is not None else (mid_rim[1] + mid_rim[3]) / 2.0
            rim1_cy = (rim1[1] + rim1[3]) / 2.0 if rim1 is not None else (mid_rim[1] + mid_rim[3]) / 2.0
        rim_slope = (rim1_cy - rim0_cy) / (right - left)
        rim_intercept = rim0_cy - rim_slope * left
        qa = a
        qb = b - rim_slope
        qc = c - rim_intercept
        if abs(qa) < 1e-12:
            if abs(qb) < 1e-12:
                continue
            roots = [-qc / qb]
        else:
            discriminant = qb * qb - 4 * qa * qc
            if discriminant < 0:
                continue
            root = math.sqrt(discriminant)
            roots = [(-qb + root) / (2 * qa), (-qb - root) / (2 * qa)]
        candidate = min(
            (
                value for value in roots
                if (
                    left <= value <= right
                    and value > 0
                    and (
                        not descending_only
                        or (
                            value >= apex_time
                            and 2 * qa * value + qb > 1e-9
                        )
                    )
                )
            ),
            default=None,
        )
        if candidate is not None and (best is None or candidate < best):
            best = candidate
    return best


def observed_cross(points, apex, fallback_rim, rim_track, rim_times):
    first_outside = None
    for idx in range(max(1, apex + 1), len(points)):
        previous, current = points[idx - 1], points[idx]
        if current[0] - previous[0] > 0.8:
            continue
        span = current[2] - previous[2]
        boundaries = {previous[0], current[0]}
        if rim_times:
            left_index = max(0, bisect_left(rim_times, previous[0]) - 1)
            right_index = min(len(rim_times), bisect_left(rim_times, current[0]) + 1)
            for index in range(left_index, right_index):
                for boundary in (
                    rim_times[index] - 1.0,
                    rim_times[index],
                    rim_times[index] + 1.0,
                ):
                    if previous[0] < boundary < current[0]:
                        boundaries.add(boundary)
                if index + 1 < len(rim_times):
                    gap = rim_times[index + 1] - rim_times[index]
                    if gap > 1.0:
                        switch = rim_times[index] + gap / 2.0
                        if previous[0] < switch < current[0]:
                            boundaries.add(switch)
        boundaries = sorted(boundaries)
        segment_dt = max(1e-9, current[0] - previous[0])
        for start, end in zip(boundaries, boundaries[1:]):
            if end <= start:
                continue
            rim0 = rim_at_time(rim_track, start, rim_times) or fallback_rim
            rim1 = rim_at_time(rim_track, end, rim_times) or fallback_rim
            ball0 = previous[2] + span * (start - previous[0]) / segment_dt
            ball1 = previous[2] + span * (end - previous[0]) / segment_dt
            d0 = ball0 - (rim0[1] + rim0[3]) / 2
            d1 = ball1 - (rim1[1] + rim1[3]) / 2
            if d0 > 1e-6 or d1 < -1e-6 or d1 - d0 <= 1e-6:
                continue
            local_fraction = 0.0 if abs(d1 - d0) < 1e-6 else min(1.0, max(0.0, -d0 / (d1 - d0)))
            cross_time = start + (end - start) * local_fraction
            ball_fraction = min(1.0, max(0.0, (cross_time - previous[0]) / segment_dt))
            cross_x = previous[1] + (current[1] - previous[1]) * ball_fraction
            cross_rim = rim_at_time(rim_track, cross_time, rim_times) or fallback_rim
            cross_cx = (cross_rim[0] + cross_rim[2]) / 2
            cross_w = cross_rim[2] - cross_rim[0]
            crossing = (
                abs(cross_x - cross_cx) <= TOL * cross_w,
                cross_time,
                cross_x,
                True,
            )
            if crossing[0]:
                return crossing
            if first_outside is None:
                first_outside = crossing
    if first_outside is not None:
        return first_outside
    below = None
    for point in points[apex + 1 :]:
        rim = rim_at_time(rim_track, point[0], rim_times) or fallback_rim
        if point[2] >= rim[3] and point[0] - points[apex][0] <= 1.6:
            below = point
            break
    if below is None:
        return None
    below_rim = rim_at_time(rim_track, below[0], rim_times) or fallback_rim
    below_cx = (below_rim[0] + below_rim[2]) / 2
    below_w = below_rim[2] - below_rim[0]
    return abs(below[1] - below_cx) <= TOL * below_w, below[0], below[1], False


def rim_distance_px(point, rim):
    return math.hypot(
        (point[1] - (rim[0] + rim[2]) / 2) * W,
        (point[2] - (rim[1] + rim[3]) / 2) * H,
    )


def local_dynamic_impact(points, apex, rim_track, rim_times):
    if not rim_track or apex >= len(points) - 1:
        return None
    start = points[0]
    start_rim = rim_at_time(rim_track, start[0], rim_times)
    if start_rim is None:
        return None
    peak_y = min(point[2] for point in points)
    started_in_flight = start[2] < (
        (start_rim[1] + start_rim[3]) / 2 - (start_rim[3] - start_rim[1]) * 4
    )
    if not started_in_flight and peak_y >= start[2] - MIN_SHOT_RISE:
        return None

    distances = []
    point_indices = []
    for index in range(apex, len(points)):
        point = points[index]
        if index > apex and point[0] - points[index - 1][0] > MAX_OBSERVED_CROSS_GAP_SECONDS:
            break
        if point[0] - points[apex][0] > MAX_IMPACT_APPROACH_SECONDS:
            break
        rim = rim_at_time(rim_track, point[0], rim_times)
        if rim is None:
            break
        distances.append(rim_distance_px(point, rim))
        point_indices.append(index)
    if len(distances) < 4:
        return None

    min_gain = max(8.0, (start_rim[2] - start_rim[0]) * W * 0.20)
    local_min = None
    for index in range(1, len(distances) - 1):
        if (
            distances[index] <= distances[index - 1]
            and distances[index] < distances[index + 1]
            and distances[0] - distances[index] >= min_gain
        ):
            local_min = index
            break
    if local_min is None:
        return None

    end_index = point_indices[local_min]
    fit_points = points[max(apex, end_index - 2):end_index + 1]
    if len(fit_points) < 3:
        return None
    mean_t = sum(point[0] for point in fit_points) / len(fit_points)
    mean_x = sum(point[1] for point in fit_points) / len(fit_points)
    mean_y = sum(point[2] for point in fit_points) / len(fit_points)
    denominator = sum((point[0] - mean_t) ** 2 for point in fit_points)
    if denominator < 1e-9:
        return None
    vx = sum((point[0] - mean_t) * (point[1] - mean_x) for point in fit_points) / denominator
    vy = sum((point[0] - mean_t) * (point[2] - mean_y) for point in fit_points) / denominator
    if vy <= 0.02:
        return None
    intercept_x = mean_x - vx * mean_t
    intercept_y = mean_y - vy * mean_t

    last = points[end_index]
    previous_y = vy * last[0] + intercept_y
    previous_rim = rim_at_time(rim_track, last[0], rim_times)
    if previous_rim is None:
        return None
    previous_rim_y = (previous_rim[1] + previous_rim[3]) / 2
    for step in range(1, MAX_IMPACT_EXTRAPOLATION_STEPS + 1):
        time_s = last[0] + step * IMPACT_STEP_SECONDS
        rim = rim_at_time(rim_track, time_s, rim_times)
        if rim is None:
            break
        y = vy * time_s + intercept_y
        rim_y = (rim[1] + rim[3]) / 2
        if previous_y <= previous_rim_y and y >= rim_y:
            x = vx * time_s + intercept_x
            error_widths = abs(x - (rim[0] + rim[2]) / 2) / max(1e-4, rim[2] - rim[0])
            return (
                error_widths <= MAX_LOCAL_IMPACT_ERROR_WIDTHS,
                time_s,
                x,
            )
        previous_y = y
        previous_rim_y = rim_y
    return None


def is_outbound_return_track(points, apex, rim_track, rim_times):
    start = points[0]
    start_rim = rim_at_time(rim_track, start[0], rim_times)
    if start_rim is None:
        return False
    start_distance = rim_distance_px(start, start_rim)
    if start_distance > (start_rim[2] - start_rim[0]) * W * OUTBOUND_START_RIM_WIDTHS:
        return False
    end = points[-1]
    end_rim = rim_at_time(rim_track, end[0], rim_times)
    if end_rim is None:
        return False
    min_distance = start_distance
    for point in points[apex:]:
        rim = rim_at_time(rim_track, point[0], rim_times)
        if rim is not None:
            min_distance = min(min_distance, rim_distance_px(point, rim))
    return (
        min_distance >= start_distance * 0.90
        and rim_distance_px(end, end_rim) > start_distance * 1.15
    )


def low_res_post_rim_event(
    track,
    track_rim,
    require_rim_evidence=False,
    rim_track=None,
    rim_times=None,
):
    if not low_res_corrected or not ALLOW_LOW_RES_MADE:
        return None
    track_rim_cx = (track_rim[0] + track_rim[2]) / 2
    track_rim_cy = (track_rim[1] + track_rim[3]) / 2
    track_rim_w = track_rim[2] - track_rim[0]
    if require_rim_evidence:
        evidence_rim = (
            rim_at_time(rim_track or [], track[-1][0], rim_times or [])
            or track_rim
        )
        evidence_cx = (evidence_rim[0] + evidence_rim[2]) / 2
        evidence_w = evidence_rim[2] - evidence_rim[0]
        if abs(track[-1][1] - evidence_cx) > 0.75 * evidence_w:
            return None
    if (
        track[0][2] < track_rim_cy
        or track[-1][2] - track[0][2] < 0.03
    ):
        return None
    release = track[0]
    release_rim = (
        rim_at_time(rim_track or [], release[0], rim_times or [])
        or track_rim
    )
    return {
        "t": round(release[0], 1),
        "resolve": round(track[-1][0], 3),
        "made": True,
        "err": round((track[-1][1] - track_rim_cx) / track_rim_w, 2),
        "observed": None,
        "dist": round(
            math.hypot(
                (release[1] - rim_cx) * W,
                (release[2] - rim_cy) * H,
            )
            / max(1e-6, (rim_w * W) / 0.4572),
            2,
        ),
        "type": estimate_shot_type(release, release_rim),
        "rise_only": False,
        "n": len(track),
        "segment": track[0][4],
    }


events = []
for track in tracklets:
    min_track_points = 4 if low_res_corrected else 5
    min_track_span = 0.20 if low_res_corrected else 0.30
    if len(track) < min_track_points or track[-1][0] - track[0][0] < min_track_span:
        continue
    segment = track[0][4]
    segment_rim_track = [point for point in trusted_rim_track if point[3] == segment]
    segment_rim_times = [point[0] for point in segment_rim_track]
    track_center = (track[0][0] + track[-1][0]) / 2
    dynamic_rim = min(segment_rim_track, key=lambda point: abs(point[0] - track_center), default=None)
    if dynamic_rim is not None and abs(dynamic_rim[0] - track_center) > 1.0:
        dynamic_rim = None
    dynamic_rim_trusted = dynamic_rim is not None and dynamic_rim[2] >= 0.28
    fallback_track_rim = (
        adapt_local_rim(tracklets, segment, track_center, engine_rim)
        if ALLOW_RIM_REFINEMENT
        else engine_rim
    )
    track_rim = (
        dynamic_rim[1]
        if dynamic_rim_trusted
        else fallback_track_rim
    )
    track_rim_cx = (track_rim[0] + track_rim[2]) / 2
    track_rim_w = track_rim[2] - track_rim[0]
    peak_y = min(point[2] for point in track)
    apex = min(range(len(track)), key=lambda idx: track[idx][2])
    apex_rim = (
        rim_at_time(segment_rim_track, track[apex][0], segment_rim_times)
        if dynamic_rim_trusted
        else None
    )
    peak_cy = (apex_rim[1] + apex_rim[3]) / 2 if apex_rim is not None else rim_cy
    peak_w = apex_rim[2] - apex_rim[0] if apex_rim is not None else rim_w
    if peak_y > peak_cy + 1.4 * peak_w:
        continue
    if dynamic_rim_trusted:
        start_rim = rim_at_time(segment_rim_track, track[0][0], segment_rim_times)
        started_in_flight = (
            start_rim is not None
            and track[0][2] < (
                (start_rim[1] + start_rim[3]) / 2 - (start_rim[3] - start_rim[1]) * 4
            )
        )
        rose_into_arc = peak_y <= track[0][2] - MIN_SHOT_RISE
        if not started_in_flight and not rose_into_arc:
            continue
    arc = track[: min(len(track), apex + 6)]
    if len(arc) < 5:
        continue
    fitted = fit_quad(arc)
    if fitted is None:
        continue
    a, b, c, _ = fitted
    if a <= 0:
        fallback = low_res_post_rim_event(
            track, track_rim, dynamic_rim_trusted, segment_rim_track, segment_rim_times,
        )
        if fallback is not None:
            events.append(fallback)
        continue

    observed = observed_cross(
        track,
        apex,
        fallback_track_rim,
        segment_rim_track,
        segment_rim_times,
    )
    impact = (
        local_dynamic_impact(track, apex, segment_rim_track, segment_rim_times)
        if dynamic_rim_trusted
        else None
    )
    if dynamic_rim_trusted and is_outbound_return_track(
        track, apex, segment_rim_track, segment_rim_times,
    ):
        continue
    predicted = False
    predicted_x = None
    resolve_t = None
    eval_x = None
    if (
        observed is None
        or (refined_rim is None and not dynamic_rim_trusted)
        or (dynamic_rim_trusted and observed is not None and not observed[3])
    ):
        apex_t = -b / (2 * a)
        prediction_start = track[0][0] + max(0.0, apex_t)
        prediction_end = track[0][0] + 3.5
        has_prediction_rim_coverage = any(
            point[0] + 1.0 >= prediction_start and point[0] - 1.0 <= prediction_end
            for point in segment_rim_track
        )
        hit_t = predicted_hit_time(
            track[0][0],
            a,
            b,
            c,
            apex_t,
            (fallback_track_rim[1] + fallback_track_rim[3]) / 2,
            segment_rim_track,
            segment_rim_times,
            refined_rim is not None or has_prediction_rim_coverage,
        )
        if hit_t is None:
            fallback = low_res_post_rim_event(
                track, track_rim, dynamic_rim_trusted, segment_rim_track, segment_rim_times,
            )
            if fallback is not None:
                events.append(fallback)
            continue
        if hit_t > 3.5:
            fallback = low_res_post_rim_event(
                track, track_rim, dynamic_rim_trusted, segment_rim_track, segment_rim_times,
            )
            if fallback is not None:
                events.append(fallback)
            continue
        times = [point[0] - track[0][0] for point in arc]
        xs = [point[1] for point in arc]
        count = len(times)
        sum_t = sum(times)
        sum_x = sum(xs)
        sum_tt = sum(value * value for value in times)
        sum_tx = sum(t * x for t, x in zip(times, xs))
        denominator = count * sum_tt - sum_t * sum_t
        if abs(denominator) < 1e-12:
            continue
        slope = (count * sum_tx - sum_t * sum_x) / denominator
        intercept = (sum_x - slope * sum_t) / count
        predicted_x = slope * hit_t + intercept
        eval_x = predicted_x
        if refined_rim is not None or dynamic_rim_trusted:
            prediction_tolerance = TOL
        elif relaxed_rough_rim_tolerance:
            prediction_tolerance = 0.75
        else:
            prediction_tolerance = 0.50
        resolve_t = track[0][0] + hit_t
        # Match Android: use the trusted rim at the evidence time even when the
        # trajectory center itself is outside the dynamic-rim association age.
        hit_rim = rim_at_time(
            segment_rim_track,
            resolve_t,
            segment_rim_times,
        ) or fallback_track_rim
        hit_cx = (hit_rim[0] + hit_rim[2]) / 2
        hit_w = hit_rim[2] - hit_rim[0]
        predicted = abs(eval_x - hit_cx) <= prediction_tolerance * hit_w
    else:
        eval_x = observed[2]
        resolve_t = observed[1]

    observed_can_confirm = (
        observed is not None
        and observed[0]
        and track[-1][0] - track[0][0] < 1.15
        and abs(observed[2] - track_rim_cx) <= (
            0.75 if relaxed_rough_rim_tolerance else 0.52
        ) * track_rim_w
    )
    if dynamic_rim_trusted:
        if observed is not None:
            made = bool(observed[0] and observed[3])
        elif impact is not None:
            made = impact[0]
        else:
            made = predicted
    elif low_res_corrected and ALLOW_LOW_RES_MADE:
        made = True
    elif refined_rim is not None:
        made = observed[0] if observed is not None else predicted
    elif relaxed_rough_rim_tolerance:
        made = predicted or observed_can_confirm
    elif observed is not None and not observed[0]:
        made = False
    else:
        made = predicted or observed_can_confirm
    # Keep the resolved time aligned with the evidence source: an observed
    # descending plane crossing wins; prediction is only the fallback.
    prediction_determines_resolution = predicted and (
        observed is None or not observed[3]
    )
    impact_determines_resolution = (
        dynamic_rim_trusted
        and not (observed is not None and observed[0])
        and impact is not None
        and impact[0]
    )
    if impact_determines_resolution:
        resolve_t = impact[1]
        eval_x = impact[2]
    elif prediction_determines_resolution:
        resolve_t = track[0][0] + hit_t
    release = track[0]
    release_rim = (
        rim_at_time(segment_rim_track, release[0], segment_rim_times)
        or track_rim
    )
    image_distance = math.hypot(
        (release[1] - rim_cx) * W,
        (release[2] - rim_cy) * H,
    ) / max(1e-6, (rim_w * W) / 0.4572)
    events.append(
        {
            "t": round(release[0], 1),
            "resolve": round(resolve_t, 3),
            "made": made,
            "err": round((eval_x - track_rim_cx) / track_rim_w, 2),
            "observed": None if observed is None else observed[0],
            "dist": round(image_distance, 2),
            "type": estimate_shot_type(release, release_rim),
            "rise_only": track[-1][2] < track[0][2],
            "n": len(track),
            "segment": segment,
        }
    )


def dedup(items):
    unique = []
    for item in items:
        if (
            unique
            and item["segment"] == unique[-1]["segment"]
            and item["t"] - unique[-1]["t"] < 1.05
        ):
            if item["made"] and not unique[-1]["made"]:
                unique[-1] = item
            continue
        unique.append(item)
    return unique


events = dedup(sorted(events, key=lambda item: item["t"]))
print(
    json.dumps(
        {
            "attempts": len(events),
            "made": sum(1 for event in events if event["made"]),
            "rim": [round(value, 6) for value in (
                trusted_rim_track[-1][1] if has_dynamic_rim else engine_rim
            )],
            "rimAdjustedPx": rim_adjusted_px,
            "rise_only": sum(1 for event in events if event["rise_only"]),
            "events": events,
        },
        ensure_ascii=False,
    )
)
