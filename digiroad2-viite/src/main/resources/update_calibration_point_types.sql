UPDATE CALIBRATION_POINT AS cpu SET TYPE = (SELECT CASE
-- leave expired calibration points and user assigned points to original value
-- [TYPE = 2] Includes templates, points where ADDR_M is equal to START_ADDR_M or END_ADDR_M of the road (road_number, road_part_number and track) and when ROAD_TYPE changes
		WHEN (rp.ADDR_M IN (SELECT roadAddr.END_ADDR_M FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL AND roadAddr.DISCONTINUITY = 4)) THEN 2  /* Minor discontinuity in previous roadway end makes second roadway start also "discontinuos" */
		WHEN (rp.ADDR_M = rw.END_ADDR_M AND rw.DISCONTINUITY IN (1,2,3,4,6)) THEN 2 /* There is DISCONTINUITY */
		WHEN (rp.ADDR_M = (SELECT MIN(roadAddr.START_ADDR_M) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 2 /* ADDR_M is equal to START_ADDR_M */
		WHEN (rp.ADDR_M = (SELECT MAX(roadAddr.END_ADDR_M) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 2 /* ADDR_M is equal to END_ADDR_M */
		WHEN ((SELECT DISTINCT(roadAddr.ROAD_TYPE) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.START_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL) !=
			(SELECT DISTINCT(roadAddr.ROAD_TYPE) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.END_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL)) THEN 2 /* ROAD_TYPE changed on ADDR_M */
		WHEN ((SELECT DISTINCT(roadAddr.TRACK) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.START_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL AND roadAddr.TRACK = 0) !=
			(SELECT DISTINCT(MAX(roadAddr.TRACK)) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.END_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL AND roadAddr.TRACK IN (1,2)))  THEN 2 /* track changed on ADDR_M */
		WHEN ((SELECT DISTINCT(max(roadAddr.TRACK)) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.START_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL AND roadAddr.TRACK IN (1,2)) !=
			(SELECT DISTINCT(roadAddr.TRACK) FROM ROADWAY roadAddr
				WHERE roadAddr.ROAD_NUMBER = rw.ROAD_NUMBER AND roadAddr.ROAD_PART_NUMBER = rw.ROAD_PART_NUMBER AND roadAddr.END_ADDR_M = rp.ADDR_M
				AND roadAddr.VALID_TO IS NULL AND roadAddr.END_DATE IS NULL AND roadAddr.TRACK = 0))  THEN 2 /* track changed on ADDR_M */
		ELSE 1
	END AS CALIBRATION_POINT_TYPE
	FROM CALIBRATION_POINT cp
		LEFT JOIN ROADWAY_POINT rp ON cp.ROADWAY_POINT_ID = rp.ID
	LEFT JOIN ROADWAY rw ON rp.ROADWAY_NUMBER = rw.ROADWAY_NUMBER AND rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
 WHERE cp.ID = cpu.ID
 LIMIT 1) WHERE cpu.type != 0 AND cpu.VALID_TO IS NULL;