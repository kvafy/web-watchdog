## 0.3.0 (WIP)

* Converted whole app from an ad-hoc lifecycle management solution to integrant.
* Added a mandatory `:id` property to sites.
* Introduced an explicit task scheduler for checking sites, allowing for concurrent
  checks and easy on-demand website checks.
* Added a mandatory `[:state :loading?]` property to sites.
