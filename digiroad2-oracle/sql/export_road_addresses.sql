-- TODO Convert to Postgis style
--
--	This script is for export most recent (only valid) road address information (road number and road part number) to the table: EXPORT_TABLE
--		* @param	roads: vector of roads that will be exported
--		* @param	road_parts: vector of road parts that will be exported
--		* @return	insert statements will be created in the EXPORT_TABLE
--			* @export	this will export the data from the current tables :
--				* LINK
--				* ROADWAY
--				* LINEAR_LOCATION
--				* ROADWAY_POINT
--				* CALIBRATION_POINT
--				* NODE
--				* NODE_POINT
--				* JUNCTION
--				* JUNCTION_POINT

--	Notes:
--		* Make sure no one else is using this script, if so, you can change the name of the output table:
--			* Make sure output table (EXPORT_TABLE) is created :
--				CREATE TABLE EXPORT_TABLE (
--					"ORDER" INT,
--					INSERT_STATEMENT VARCHAR2(1000)
--				);

--			* Make sure output table is empty :
--				DELETE FROM EXPORT_TABLE;

--			* Delete table if necessary :
--				DROP TABLE EXPORT_TABLE PURGE;

--	Usage:
--		1. Set up road address information you are about to export (find goto:121)
--		2. There are a few different ways for exporting the result set for a .txt file,
--			but here are a few queries to check your result set before copying it :

--			* This will fetch only unique statements to prevent inserting duplicate rows on the dst database
--				(e.g.: If two exported roads are connected by one junction, it will only get 1 insert statement for that junction)

--			SELECT rs.* FROM (
--				SELECT MIN(e."ORDER") "ORDER", e.INSERT_STATEMENT
--					FROM EXPORT_TABLE e
--					GROUP BY e.INSERT_STATEMENT) rs
--				ORDER BY rs."ORDER";


--			* For checking which road addresses were processed

--			SELECT e.* FROM EXPORT_TABLE e
--				WHERE e.INSERT_STATEMENT LIKE '%Export road address information%'
--				OR e.INSERT_STATEMENT LIKE 'BEGIN' OR e.INSERT_STATEMENT LIKE 'END%'
--				ORDER BY e."ORDER";


DECLARE
	TYPE ROAD_PART_BY_RN	IS VARRAY(5) OF INTEGER;		-- Up to 5 road parts for each road number (increase this if needed)
	TYPE ROAD_ADDRESS		IS TABLE OF ROAD_PART_BY_RN		-- Associative array type (road parts)
		INDEX BY PLS_INTEGER;									--	indexed by road number;

	road_section			ROAD_ADDRESS;
	road_parts				ROAD_PART_BY_RN;

	ROAD					INTEGER;
	RP_INDEX				INTEGER;
	ROAD_PART				INTEGER;

	COUNTER					INTEGER;
	elem_info_array			VARCHAR2(1000)	:= 'MDSYS.SDO_ELEM_INFO_ARRAY(';
	ordinate_array			VARCHAR2(1000)	:= 'MDSYS.SDO_ORDINATE_ARRAY(';
	junction_number			INTEGER			:= 0;			-- Default JUNCTION_NUMBER, for both templates and junctions without junction_number;
	node_number				VARCHAR2(100)	:= 'NULL';		-- Default NODE_NUMBER for templates;

	CURSOR links (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT l.* FROM LINK l WHERE l.ID IN (
		SELECT ll.LINK_ID FROM LINEAR_LOCATION ll
			WHERE ll.VALID_TO IS NULL
			AND ll.ROADWAY_NUMBER IN (
				SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw
					WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL
					AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART));

	CURSOR roads (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT rw.* FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART;

	CURSOR linear_locations (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT ll.* FROM LINEAR_LOCATION ll WHERE ll.VALID_TO IS NULL AND ll.ROADWAY_NUMBER IN (
		SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART);

	CURSOR rw_points (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT rp.* FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
		SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART);

	CURSOR cal_points (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT cp.* FROM CALIBRATION_POINT cp WHERE cp.VALID_TO IS NULL AND cp.ROADWAY_POINT_ID IN (
		SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
			SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART));

	CURSOR nodes (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT n.* FROM NODE n WHERE n.VALID_TO IS NULL AND n.END_DATE IS NULL AND n.NODE_NUMBER IN (
		SELECT np.NODE_NUMBER FROM NODE_POINT np WHERE np.VALID_TO IS NULL AND np.ROADWAY_POINT_ID IN (
			SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
				SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART)
		) UNION
		SELECT j.NODE_NUMBER FROM JUNCTION j
			JOIN JUNCTION_POINT jp ON (j.ID = jp.JUNCTION_ID)
			WHERE j.VALID_TO IS NULL AND j.END_DATE IS NULL
			AND jp.VALID_TO IS NULL AND jp.ROADWAY_POINT_ID IN (
				SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
					SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART)));

	CURSOR node_points (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT * FROM NODE_POINT np WHERE np.VALID_TO IS NULL AND np.ROADWAY_POINT_ID IN (
		SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
			SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART));

	CURSOR junctions (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT DISTINCT j.* FROM JUNCTION j
		JOIN JUNCTION_POINT jp ON (j.ID = jp.JUNCTION_ID)
		WHERE j.VALID_TO IS NULL AND j.END_DATE IS NULL
		AND jp.VALID_TO IS NULL AND jp.ROADWAY_POINT_ID IN (
			SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
				SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART));

	CURSOR junction_points (ROAD IN INTEGER, ROAD_PART IN INTEGER) IS SELECT jp.* FROM JUNCTION_POINT jp
		JOIN JUNCTION j ON (jp.JUNCTION_ID = j.ID)
		WHERE j.VALID_TO IS NULL AND j.END_DATE IS NULL
		AND jp.VALID_TO IS NULL AND jp.ROADWAY_POINT_ID IN (
			SELECT rp.ID FROM ROADWAY_POINT rp WHERE rp.ROADWAY_NUMBER IN (
				SELECT rw.ROADWAY_NUMBER FROM ROADWAY rw WHERE rw.VALID_TO IS NULL AND rw.END_DATE IS NULL AND rw.ROAD_NUMBER = ROAD AND rw.ROAD_PART_NUMBER = ROAD_PART));
BEGIN
	-- TODO	Add elements (key-value pairs) to associative array :
	-- e.g.:
	--	1. road_section(5)		:= (road_part_by_rn(205, 206));		-- This will export data from: road_number = 5 and road_part_number in (205, 206);
	--	2. road_section(75)		:= (road_part_by_rn(1));			-- This will export data from: road_number = 75 and road_part_number = 1
	road_section(5)		:= (road_part_by_rn(205, 206));
	road_section(75)	:= (road_part_by_rn(1));

	-- Export:
	ROAD := road_section.FIRST;
	SELECT CASE WHEN MAX("ORDER") IS NOT NULL THEN MAX("ORDER") + 1 ELSE 1 END AS value INTO COUNTER FROM EXPORT_TABLE;
	INSERT INTO EXPORT_TABLE VALUES (COUNTER, 'BEGIN');
	COUNTER := COUNTER + 1;
	WHILE ROAD IS NOT NULL LOOP
		road_parts := road_section(ROAD);
		RP_INDEX := road_parts.FIRST;
		WHILE RP_INDEX IS NOT NULL LOOP
			ROAD_PART := road_parts(RP_INDEX);
			INSERT INTO EXPORT_TABLE VALUES (COUNTER, '-- Export road address information for:	ROAD_NUMBER = ' || ROAD || ' AND ROAD_PART_NUMBER = ' || ROAD_PART);
			COUNTER := COUNTER + 1;

			--	LINK
			FOR link IN links (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO LINK (ID,"SOURCE",ADJUSTED_TIMESTAMP,CREATED_TIME) VALUES (' ||
					link.ID || ',' || link."SOURCE" || ',' || link.ADJUSTED_TIMESTAMP || ',''' || link.CREATED_TIME || ''');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	ROADWAY
			FOR roadway IN roads (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO ROADWAY (ID,ROADWAY_NUMBER,ROAD_NUMBER,ROAD_PART_NUMBER,TRACK,' ||
				'START_ADDR_M,END_ADDR_M,REVERSED,DISCONTINUITY,START_DATE,' ||
				'CREATED_BY,CREATED_TIME,ROAD_TYPE,ELY,TERMINATED,VALID_FROM) VALUES (' ||
					roadway.ID || ',' || roadway.ROADWAY_NUMBER || ',' || roadway.ROAD_NUMBER || ',' || roadway.ROAD_PART_NUMBER || ',' || roadway.TRACK || ',' ||
					roadway.START_ADDR_M || ',' || roadway.END_ADDR_M || ',' || roadway.REVERSED || ',' || roadway.DISCONTINUITY || ',''' || roadway.START_DATE || ''',''' ||
					roadway.CREATED_BY || ''',''' || roadway.CREATED_TIME || ''',' || roadway.ROAD_TYPE || ',' || roadway.ELY || ',' || roadway.TERMINATED || ',''' || roadway.VALID_FROM || ''');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	LINEAR_LOCATION
			FOR loc IN linear_locations (ROAD, ROAD_PART) LOOP
				FOR e IN (SELECT TO_CHAR(column_value) value FROM TABLE(
					SELECT ll.GEOMETRY.SDO_ELEM_INFO FROM LINEAR_LOCATION ll
						WHERE ll.ID = loc.ID))
				LOOP
					elem_info_array := CONCAT(elem_info_array, CONCAT(REPLACE(e.value, ',', '.'), ','));
				END LOOP;
		  		elem_info_array := SUBSTR(elem_info_array, 1, LENGTH(elem_info_array) - 1) || ')';

				FOR o IN (SELECT TO_CHAR(column_value) value FROM TABLE(
					SELECT ll.GEOMETRY.SDO_ORDINATES FROM LINEAR_LOCATION ll
						WHERE ll.ID = loc.ID))
				LOOP
					ordinate_array := CONCAT(ordinate_array, CONCAT(REPLACE(o.value, ',', '.'), ','));
				END LOOP;
		  		ordinate_array := SUBSTR(ordinate_array, 1, LENGTH(ordinate_array) - 1) || ')';

		  		INSERT INTO EXPORT_TABLE VALUES (COUNTER,
		  		'INSERT INTO LINEAR_LOCATION (ID,ROADWAY_NUMBER,ORDER_NUMBER,LINK_ID,START_MEASURE,END_MEASURE,SIDE,GEOMETRY,VALID_FROM,CREATED_BY,CREATED_TIME) VALUES (' ||
					loc.ID || ',' || loc.ROADWAY_NUMBER || ',' || loc.ORDER_NUMBER || ',' || loc.LINK_ID || ',' ||
					REPLACE(TO_CHAR(loc.START_MEASURE), ',', '.') || ',' || REPLACE(TO_CHAR(loc.END_MEASURE), ',', '.') || ',' || loc.SIDE || ',' ||
					'MDSYS.SDO_GEOMETRY(' || loc.GEOMETRY.SDO_GTYPE || ',' || loc.GEOMETRY.SDO_SRID || ',' || 'NULL' || ',' ||
					elem_info_array || ',' || ordinate_array || '),''' ||
					loc.VALID_FROM || ''',''' || loc.CREATED_BY || ''',''' || loc.CREATED_TIME || ''');');
				COUNTER := COUNTER + 1;
				elem_info_array := 'MDSYS.SDO_ELEM_INFO_ARRAY(';
				ordinate_array := 'MDSYS.SDO_ORDINATE_ARRAY(';
			END LOOP;

			--	ROADWAY_POINT
			FOR rw_point IN rw_points (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO ROADWAY_POINT (ID,ROADWAY_NUMBER,ADDR_M,CREATED_BY,' ||
				'CREATED_TIME,MODIFIED_BY,MODIFIED_TIME) VALUES (' ||
					rw_point.ID || ',' || rw_point.ROADWAY_NUMBER || ',' || rw_point.ADDR_M || ',''' || rw_point.CREATED_BY || ''',''' ||
					rw_point.CREATED_TIME || ''',''' || rw_point.MODIFIED_BY || ''',''' || rw_point.MODIFIED_TIME || ''');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	CALIBRATION_POINT
			FOR cal_point IN cal_points (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO CALIBRATION_POINT (ID,ROADWAY_POINT_ID,LINK_ID,START_END,' ||
				'"TYPE",VALID_FROM,CREATED_BY,CREATED_TIME) VALUES (' ||
					cal_point.ID || ',' || cal_point.ROADWAY_POINT_ID || ',' || cal_point.LINK_ID || ',' || cal_point.START_END || ',' ||
					cal_point."TYPE" || ',''' || cal_point.VALID_FROM || ''',''' || cal_point.CREATED_BY || ''',''' || cal_point.CREATED_TIME || ''');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	NODE
			FOR node IN nodes (ROAD, ROAD_PART) LOOP
				FOR e IN (SELECT TO_CHAR(column_value) value FROM TABLE(
					SELECT n.COORDINATES.SDO_ELEM_INFO FROM NODE n
						WHERE n.ID = node.ID))
				LOOP
					elem_info_array := CONCAT(elem_info_array, CONCAT(REPLACE(e.value, ',', '.'), ','));
				END LOOP;
		  		elem_info_array := SUBSTR(elem_info_array, 1, LENGTH(elem_info_array) - 1) || ')';

				FOR o IN (SELECT TO_CHAR(column_value) value FROM TABLE(
					SELECT n.COORDINATES.SDO_ORDINATES FROM NODE n
						WHERE n.ID = node.ID))
				LOOP
					ordinate_array := CONCAT(ordinate_array, CONCAT(REPLACE(o.value, ',', '.'), ','));
				END LOOP;
		  		ordinate_array := SUBSTR(ordinate_array, 1, LENGTH(ordinate_array) - 1) || ')';

				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO NODE (ID, NODE_NUMBER, COORDINATES, NAME, "TYPE", START_DATE, CREATED_BY, CREATED_TIME, VALID_FROM) VALUES (' ||
					node.ID || ',' || node.NODE_NUMBER || ',' ||
					'MDSYS.SDO_GEOMETRY(' || node.COORDINATES.SDO_GTYPE || ',' || node.COORDINATES.SDO_SRID || ',' || 'NULL' || ',' ||
					elem_info_array || ',' || ordinate_array || '),''' ||
					node.NAME || ''',' || node."TYPE" || ',''' || node.START_DATE || ''',''' || node.CREATED_BY || ''',''' ||
					node.CREATED_TIME || ''',''' || node.VALID_FROM || ''');');
				COUNTER := COUNTER + 1;
				elem_info_array := 'MDSYS.SDO_ELEM_INFO_ARRAY(';
				ordinate_array := 'MDSYS.SDO_ORDINATE_ARRAY(';
			END LOOP;

			--	NODE_POINT
			FOR np IN node_points (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO NODE_POINT (ID,BEFORE_AFTER,ROADWAY_POINT_ID,VALID_FROM,' ||
				'CREATED_BY,CREATED_TIME,"TYPE") VALUES (' ||
					np.ID || ',' || np.BEFORE_AFTER || ',' || np.ROADWAY_POINT_ID || ',''' || np.VALID_FROM || ''',''' ||
					np.CREATED_BY || ''',''' || np.CREATED_TIME || ''',' || np."TYPE" || ');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	JUNCION
			FOR j IN junctions (ROAD, ROAD_PART) LOOP
				IF j.JUNCTION_NUMBER IS NOT NULL AND j.NODE_NUMBER IS NOT NULL THEN
					junction_number := j.JUNCTION_NUMBER;
				ELSE
					junction_number := 0; -- templates will always be set as junction_number = 0 instead of NULL
				END IF;

				IF j.NODE_NUMBER IS NOT NULL THEN
					node_number := TO_CHAR(j.NODE_NUMBER);
				ELSE
					node_number := 'NULL';
				END IF;

				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO JUNCTION (ID,JUNCTION_NUMBER,START_DATE,CREATED_BY,CREATED_TIME,VALID_FROM,NODE_NUMBER) VALUES (' ||
					j.ID || ',' || junction_number || ',''' || j.START_DATE || ''',''' ||
					j.CREATED_BY || ''',''' || j.CREATED_TIME || ''',''' || j.VALID_FROM || ''',' ||
					node_number || ');');
				COUNTER := COUNTER + 1;
			END LOOP;

			--	JUNCTION_POINT
			FOR jp IN junction_points (ROAD, ROAD_PART) LOOP
				INSERT INTO EXPORT_TABLE VALUES (COUNTER,
				'INSERT INTO JUNCTION_POINT (ID,BEFORE_AFTER,ROADWAY_POINT_ID,JUNCTION_ID,VALID_FROM,CREATED_BY,CREATED_TIME) VALUES (' ||
					jp.ID || ',' || jp.BEFORE_AFTER || ',' || jp.ROADWAY_POINT_ID || ',' || jp.JUNCTION_ID || ',''' || jp.VALID_FROM || ''',''' || jp.CREATED_BY || ''',''' || jp.CREATED_TIME || ''');');
				COUNTER := COUNTER + 1;
			END LOOP;

			RP_INDEX := road_section(ROAD).NEXT(RP_INDEX);
			COMMIT;
		END LOOP;
		ROAD := road_section.NEXT(ROAD);
	END LOOP;
	INSERT INTO EXPORT_TABLE VALUES (COUNTER, 'END;');
	COMMIT;
END;