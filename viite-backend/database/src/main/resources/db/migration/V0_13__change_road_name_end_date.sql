UPDATE road_name rn
  SET end_date = end_date - INTERVAL '1 DAY'
WHERE
  EXISTS (SELECT 4 -- an existence is enough, 4 is just a dummy
   FROM road_name rn2 -- there exists another road name entry
   WHERE rn.road_number=rn2.road_number -- with the same road number
   AND rn.end_date = rn2.start_date -- that starts when this one ends (thus, 1 day overlap)
   AND rn2.valid_to IS NULL -- and that one is valid, too
   )
  AND rn.start_date != rn.end_date -- do not allow the end date to go beyond the start date
  AND rn.valid_to IS NULL; -- change only valid rows
