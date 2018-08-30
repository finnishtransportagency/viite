CREATE UNIQUE INDEX "IND_ROAD_ADDRESS_DUP" ON
    "ROAD_ADDRESS" (
        road_number,
        road_part_number,
        track_code,
        discontinuity,
        start_addr_m,
        end_addr_m,
        start_date,
        end_date,
        valid_from,
        calibration_points,
        floating,
        valid_to,
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