CREATE UNIQUE INDEX duplicate_road_address ON
    road_address (
        road_number,
        road_part_number,
        track_code,
        discontinuity,
        start_addr_m,
        end_addr_m,
        start_date,
    nvl(
        end_date,'01.01.0001'),
        valid_from,
    nvl(
        valid_to,'01.01.0001'),
        calibration_points,
        floating,
        ely,
        road_type,
        terminated,
        common_history_id,
        side_code,
        start_measure,
        end_measure,
        link_id,
        link_source
    );