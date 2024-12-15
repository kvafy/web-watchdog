## 0.3.0 (WIP)

* Added a UI dialog for adding new/editing existing checked sites.
* Each site can specify its own checking schedule using a CRON expression, instead of a global
  fixed check interval.
* Added several new site properties: `:id`, `:schedule`, `[:state :ongoing-check]`,
  `[:state :next-check-time]`.
* Introduced an explicit task scheduler for checking sites, allowing for concurrent checks and easy
  on-demand website checks. Next check of a site is primarily determined by its
  `[:state :next-check-time]` property.
* Converted the whole server from an ad-hoc lifecycle management solution to Integrant. This allows
  for true integration tests.
* Renamed `*-utc` state properties to `*-time`.
