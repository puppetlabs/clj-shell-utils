(defproject puppetlabs/clj-shell-utils "2.0.3-SNAPSHOT"
  :description "Clojure shell execution utilities"

  :min-lein-version "2.9.0"

  :parent-project {:coords [puppetlabs/clj-parent "7.3.15"]
                   :inherit [:managed-dependencies]}
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0.txt"}
            
  :pedantic? :abort

  :test-paths ["test/unit"]

  :plugins [[lein-project-version "0.1.0"]
            [lein-parent "0.3.6"]
            [puppetlabs/i18n "0.9.0"]]

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]

  :dependencies [[org.clojure/clojure]
                 [prismatic/schema]
                 [org.apache.commons/commons-exec]
                 [commons-io]
                 [org.slf4j/log4j-over-slf4j]
                 [org.slf4j/slf4j-api]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/kitchensink]
                 [puppetlabs/i18n]]



  :profiles { :test { :dependencies [[puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]]}}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]
                        ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-snapshots__local/"]])



