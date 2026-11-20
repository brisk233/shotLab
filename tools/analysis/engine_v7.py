"""PC 复算器：与 Android TrackletEngine 使用同一套轨迹和命中规则。"""
import json
import math
import os
import sys

SRC = sys.argv[1]
RIM = tuple(float(x) for x in sys.argv[2].split(","))
data = json.load(open(SRC, encoding="utf-8"))
W, H = data["w"], data["h"]
rim_cx = (RIM[0] + RIM[2]) / 2
rim_cy = (RIM[1] + RIM[3]) / 2
rim_w = RIM[2] - RIM[0]
TOL = float(sys.argv[3]) if len(sys.argv) > 3 else 0.55

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


def adapt_local_rim(tracks, center_time, base):
    base_w = base[2] - base[0]
    base_h = base[3] - base[1]
    base_cx = (base[0] + base[2]) / 2
    base_cy = (base[1] + base[3]) / 2
    local = []
    for points in tracks:
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
    next_active = []
    used = set()
    for idx, missed in active:
        track = tracklets[idx]
        last_t, last_x, last_y, _ = track[-1]
        dt = max(1e-3, t - last_t)
        if dt > MAX_TRACK_GAP_SECONDS:
            continue
        if len(track) >= 2:
            prev_t, prev_x, prev_y, _ = track[-2]
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
            track.append((t, x, y, confidence))
            next_active.append((idx, 0))
        elif missed < MAX_MISSED_FRAMES:
            next_active.append((idx, missed + 1))
    for candidate_idx, candidate in enumerate(frame["cands"]):
        if candidate_idx in used:
            continue
        x, y = center(candidate)
        if inside_person((x, y), frame):
            continue
        tracklets.append([(t, x, y, candidate[4])])
        next_active.append((len(tracklets) - 1, 0))
    active = next_active


refined_rim = refine_rim_from_tracks(tracklets, RIM)
engine_rim = refined_rim[0] if refined_rim is not None else RIM
rim_adjusted_px = refined_rim[1] if refined_rim is not None else 0
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
    for t, _, y, _ in points:
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


def observed_cross(points, apex, rim):
    rim_cx = (rim[0] + rim[2]) / 2
    rim_cy = (rim[1] + rim[3]) / 2
    rim_w = rim[2] - rim[0]
    first_outside = None
    for idx in range(max(1, apex + 1), len(points)):
        previous, current = points[idx - 1], points[idx]
        if current[0] - previous[0] > 0.8:
            continue
        if previous[2] >= rim_cy or current[2] < rim_cy:
            continue
        span = current[2] - previous[2]
        if abs(span) < 1e-6:
            continue
        fraction = min(1.0, max(0.0, (rim_cy - previous[2]) / span))
        cross_x = previous[1] + (current[1] - previous[1]) * fraction
        cross_t = previous[0] + (current[0] - previous[0]) * fraction
        crossing = (abs(cross_x - rim_cx) <= TOL * rim_w, cross_t, cross_x)
        if crossing[0]:
            return crossing
        if first_outside is None:
            first_outside = crossing
    if first_outside is not None:
        return first_outside
    below = next(
        (
            point
            for point in points[apex + 1 :]
            if point[2] >= rim[3] and point[0] - points[apex][0] <= 1.6
        ),
        None,
    )
    if below is None:
        return None
    return abs(below[1] - rim_cx) <= TOL * rim_w, below[0], below[1]


def low_res_post_rim_event(track, track_rim):
    if not low_res_corrected:
        return None
    track_rim_cx = (track_rim[0] + track_rim[2]) / 2
    track_rim_cy = (track_rim[1] + track_rim[3]) / 2
    track_rim_w = track_rim[2] - track_rim[0]
    if (
        track[0][2] < track_rim_cy
        or track[-1][2] - track[0][2] < 0.03
    ):
        return None
    release = track[0]
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
        "type": estimate_shot_type(release, track_rim),
        "rise_only": False,
        "n": len(track),
    }


events = []
for track in tracklets:
    min_track_points = 4 if low_res_corrected else 5
    min_track_span = 0.20 if low_res_corrected else 0.30
    if len(track) < min_track_points or track[-1][0] - track[0][0] < min_track_span:
        continue
    track_center = (track[0][0] + track[-1][0]) / 2
    track_rim = adapt_local_rim(tracklets, track_center, engine_rim)
    track_rim_cx = (track_rim[0] + track_rim[2]) / 2
    track_rim_cy = (track_rim[1] + track_rim[3]) / 2
    track_rim_w = track_rim[2] - track_rim[0]
    peak_y = min(point[2] for point in track)
    if peak_y > rim_cy + 1.4 * rim_w:
        continue
    apex = min(range(len(track)), key=lambda idx: track[idx][2])
    arc = track[: min(len(track), apex + 6)]
    if len(arc) < 5:
        continue
    fitted = fit_quad(arc)
    if fitted is None:
        continue
    a, b, c, _ = fitted
    if a <= 0:
        fallback = low_res_post_rim_event(track, track_rim)
        if fallback is not None:
            events.append(fallback)
        continue

    observed = observed_cross(track, apex, track_rim)
    predicted = False
    predicted_x = None
    resolve_t = None
    eval_x = None
    if observed is None or refined_rim is None:
        discriminant = b * b - 4 * a * (c - track_rim_cy)
        if discriminant < 0:
            fallback = low_res_post_rim_event(track, track_rim)
            if fallback is not None:
                events.append(fallback)
            continue
        apex_t = -b / (2 * a)
        roots = [
            (-b + math.sqrt(discriminant)) / (2 * a),
            (-b - math.sqrt(discriminant)) / (2 * a),
        ]
        if refined_rim is None:
            hit_t = min((root for root in roots if root > 0), default=None)
        else:
            hit_t = min((root for root in roots if root > 0 and root >= apex_t), default=None)
        if hit_t is None:
            fallback = low_res_post_rim_event(track, track_rim)
            if fallback is not None:
                events.append(fallback)
            continue
        if hit_t > 3.5:
            fallback = low_res_post_rim_event(track, track_rim)
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
        if refined_rim is not None:
            prediction_tolerance = TOL
        elif relaxed_rough_rim_tolerance:
            prediction_tolerance = 0.75
        else:
            prediction_tolerance = 0.50
        predicted = abs(eval_x - track_rim_cx) <= prediction_tolerance * track_rim_w
        resolve_t = track[0][0] + hit_t
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
    if low_res_corrected:
        made = True
    elif refined_rim is not None:
        made = observed[0] if observed is not None else predicted
    elif relaxed_rough_rim_tolerance:
        made = predicted or observed_can_confirm
    elif observed is not None and not observed[0]:
        made = False
    else:
        made = predicted or observed_can_confirm
    release = track[0]
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
            "observed": None if observed is None else made,
            "dist": round(image_distance, 2),
            "type": estimate_shot_type(release, track_rim),
            "rise_only": track[-1][2] < track[0][2],
            "n": len(track),
        }
    )


def dedup(items):
    unique = []
    for item in items:
        if unique and item["t"] - unique[-1]["t"] < 1.05:
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
            "rim": [round(value, 6) for value in engine_rim],
            "rimAdjustedPx": rim_adjusted_px,
            "rise_only": sum(1 for event in events if event["rise_only"]),
            "events": events,
        },
        ensure_ascii=False,
    )
)
