## 2.0.2

* Add an optional `:timeout-ms` execution option to `execute-command` and
  `execute-command-streamed`. When set, a process that runs longer than the
  timeout is forcibly terminated (`Process#destroyForcibly()`) and the
  result's new `:timed-out` entry is `true`. Omitting `:timeout-ms` preserves
  prior behavior exactly (blocks indefinitely, `:timed-out` is always
  `false`).

## 2.0.1 release version of 2.0.0
## 2.0.0 - not released
* update to clj parent 7.3.15
* add in the i18n plugin, and Makefiles

## 1.0.2

* Built with the same code as 1.0.0, but with java8 instead of java11

## 1.0.0

* Initial release
