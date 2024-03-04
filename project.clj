(defproject io.jepsen/rds "0.1.0-SNAPSHOT"
  :description "Support for launching RDS clusters for Jepsen tests"
  :url "https://github.com/jepsen-io/rds"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.cognitect.aws/api "0.8.692"]
                 [com.cognitect.aws/ec2 "848.2.1413.0"]
                 [com.cognitect.aws/endpoints "1.1.12.626"]
                 [com.cognitect.aws/rds "848.2.1413.0"]
                 [http-kit "2.7.0"]
                 [slingshot "0.12.2"]
                 [org.clojure/tools.logging "1.3.0"]]
  :repl-options {:init-ns jepsen.rds})
