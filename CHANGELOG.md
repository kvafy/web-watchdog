## 0.3.2

* Added UI action "Use as template" to trigger a site creation dialog pre-populated with properties
  of the given existing site.

## 0.3.1

* Added an optional `[:request :url]` property. If present, the site content is read from this URL,
  while the top-level site `:url` property is considered a "display" URL for the user, e.g. in
  email notifications or in the UI. Useful for sites whose content is programmatically consumed via
  an API, while there is also a human-readable version of the same information available at another
  URL.
* Added a convenience CRON script under `scripts/backup-state.sh` for daily updates on the mutable
  config/state file.
* Favicons of the monitored websites are now displayed in the web UI.
* Added favicon for the tool's web UI.

## 0.3.0

* Added a UI dialog for adding new/editing existing checked sites.
* Each site can specify its own checking schedule using a CRON expression, instead of a global
  fixed check interval.
* Added several new site properties: `:id`, `:request`, `:schedule`, `[:state :ongoing-check]`,
  `[:state :next-check-time]`.
* Introduced an explicit task scheduler for checking sites, allowing for concurrent checks and easy
  on-demand website checks. Next check of a site is primarily determined by its
  `[:state :next-check-time]` property.
* Converted the whole server from an ad-hoc lifecycle management solution to Integrant. This allows
  for true integration tests.
* Renamed `*-utc` state properties to `*-time`.
