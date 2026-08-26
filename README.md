# puppetlabs/clj-shell-utils

A library for shell execution common to Puppet clojure projects.

## Installation

Add the following dependency to your `project.clj` file:

[![Clojars Project](http://clojars.org/puppetlabs/clj-shell-utils/latest-version.svg)](http://clojars.org/puppetlabs/clj-shell-utils)

## Usage

`puppetlabs.puppetserver.shell-utils/execute-command` and `execute-command-streamed`
execute an external command and return a map with `:exit-code`, `:stderr`,
`:stdout`, and `:timed-out`.

By default, these functions block on the process indefinitely, exactly as in
prior releases. To bound how long a command is allowed to run, pass a
`:timeout-ms` entry in the options map:

```clojure
(execute-command "/opt/puppetlabs/bin/some-script"
                  {:args ["arg1" "arg2"]
                   :timeout-ms 30000})
```

If the process has not completed within `:timeout-ms` milliseconds, it is
forcibly terminated (`Process#destroyForcibly()`, i.e. `SIGKILL` on POSIX
systems -- not a plain `SIGTERM`/`.destroy()`, since some shells defer or
ignore that signal while waiting on a foreground child of their own) and the
result's `:timed-out` entry is `true`. If the process completes on its own,
whether with a zero or non-zero exit code, `:timed-out` is `false`. Omitting
`:timeout-ms` also always yields `:timed-out false`.

Note: if the timed-out process has itself forked a child process that
inherited its stdout/stderr (e.g. a shell script whose *last* action is not a
tail-called `exec`), that grandchild is not directly targeted by the forced
kill and may keep running briefly until it exits on its own. This library
still bounds how long the caller can be blocked waiting for output in that
case, but does not guarantee the entire process tree is reaped immediately.

## License

Copyright © 2019 Puppet Labs

See [LICENSE](LICENSE) file.

## Support

Please log tickets and issues at our [JIRA tracker](https://tickets.puppetlabs.com/).

We use semantic version numbers for our releases, and recommend that users stay
as up-to-date as possible by upgrading to patch releases and minor releases as
they become available.

Bugfixes and ongoing development will occur in minor releases for the current
major version. Security fixes will be backported to a previous major version on
a best-effort basis, until the previous major version is no longer maintained.

