## 0.3.0 (WIP)

* Each site can specify its own checking schedule using a CRON expression, instead of a global
  fixed check interval.
* Added several new site properties: `:id`, `:schedule`, `[:state :ongoing-check]`,
  `[:state :next-check-utc]`.
* Introduced an explicit task scheduler for checking sites, allowing for concurrent checks and easy
  on-demand website checks. Next check of a site is primarily determined by its
  `[:state :next-check-utc]` property.
* Converted whole app from an ad-hoc lifecycle management solution to Integrant. This allows for
  true integration tests.
