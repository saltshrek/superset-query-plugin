-- Pairs of channels with the most members in common. A self-join pairs
-- each member's channels.
SELECT
  a.channel_name AS channel,
  b.channel_name AS other_channel,
  COUNT(*) AS shared_members
FROM members_channels_2 AS a
JOIN members_channels_2 AS b
  ON b.member_name = a.member_name
  AND b.channel_name > a.channel_name
GROUP BY a.channel_name, b.channel_name
ORDER BY shared_members DESC
LIMIT 20;
