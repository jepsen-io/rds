(ns jepsen.rds
  "AWS client operations and creating/tearing down RDS DBs."
  (:require [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as aws.creds]
            [org.httpkit.client :as http]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn await-fn
  "Invokes a function (f) repeatedly. Blocks until (f) returns, rather than
  throwing. Returns that return value. Catches Exceptions (except for
  InterruptedException) and retries them automatically. Options:

    :retry-interval   How long between retries, in ms. Default 1s.
    :log-interval     How long between logging that we're still waiting, in ms.
                      Default `retry-interval.
    :log-message      What should we log to the console while waiting?
    :timeout          How long until giving up and throwing :type :timeout, in
                      ms. Default 60 seconds."
  ([f]
   (await-fn f {}))
  ([f opts]
   (let [log-message    (:log-message opts (str "Waiting for " f "..."))
         retry-interval (long (:retry-interval opts 1000))
         log-interval   (:log-interval opts retry-interval)
         timeout        (:timeout opts 60000)
         t0             (System/nanoTime)
         log-deadline   (atom (+ t0 (* 1e6 log-interval)))
         deadline       (+ t0 (* 1e6 timeout))]
     (loop []
       (let [res (try
                   (f)
                   (catch InterruptedException e
                     (throw e))
                   (catch Exception e
                     (let [now (System/nanoTime)]
                       ; Are we out of time?
                       (when (<= deadline now)
                         (throw+ {:type :timeout} e))

                       ; Should we log something?
                       (when (<= @log-deadline now)
                         (info log-message)
                         (swap! log-deadline + (* log-interval 1e6)))

                       ; Right, sleep and retry
                       (Thread/sleep retry-interval)
                       ::retry)))]
         (if (= ::retry res)
           (recur)
           res))))))

(def aws-tag
  "The tag we use to identify our auto-generated resources."
  {:Key "jepsen.rds"})

(def public-security-group
  "The name of a security group that allows access from this machine's public
  IP."
  "jepsen-public-sg")

(defn public-ip-
  "Public IP of this machine."
  []
  (:body @(http/get "https://api.ipify.org")))

(def public-ip (memoize public-ip-))

(defn aws-config-file
  "Location of the AWS config file"
  []
  (io/file (System/getenv "HOME") ".aws" "config"))

(defn config
  "Loads a configuration map from AWS config file (e.g. (~/.aws/config)"
  []
  (let [f  (aws-config-file)
        fn (.getCanonicalPath f)
        s  (slurp f)
        ; hack hack hack
        [_ secret-access-key] (re-find #"aws_secret_access_key\s+=\s+([^\s]+)"
                                       s)
        [_ access-key-id]     (re-find #"aws_access_key_id\s+=\s+([^\s]+)" s)
        [_ region]            (re-find #"region\s+=\s+([^\s]+)" s)]
    (assert secret-access-key
            (str "Unable to parse secret access key from " fn))
    (assert access-key-id
            (str "Unable to parse access key id from " fn))
    (assert region
            (str "Unable to parse region from " fn))
    {:region region
     :secret-access-key secret-access-key
     :access-key-id access-key-id}))

(defn aws-client
  "Constructs a new AWS client for the given API (e.g. :rds) using local AWS
  credentials if they exist."
  [api]
  (let [basic-creds (config)]
    (cond-> {:api api}
      basic-creds (assoc :credentials-provider
                         (aws.creds/basic-credentials-provider basic-creds))
      true        aws/client)))

(defn aws-invoke
  "Like aws/invoke, but throws exceptions on errors. I... I have questions."
  [client op]
  (let [r (aws/invoke client op)]
    (if (contains? r :cognitect.anomalies/category)
      (throw+ (assoc r :type :aws-error))
      r)))

(defn vpc-id
  "Gets the id of the default VPC to use. We just choose the first."
  []
  (let [vpcs (aws-invoke (aws-client :ec2) {:op :DescribeVpcs})
        vpc (->> vpcs
                 :Vpcs
                 (filter :IsDefault)
                 first)]
    (assert vpc (str "No default VPC found!\n" (with-out-str (pprint vpcs))))
    (:VpcId vpc)))

(defn default-subnet-ids
  "Lists all default-for-az subnets for a given VPC ID."
  [vpc-id]
  (let [subnets (aws-invoke (aws-client :ec2)
                            {:op :DescribeSubnets
                             :request {:filters [{:name "vpc-id"
                                                  :values [vpc-id]}]}})]
    (->> subnets
         :Subnets
         (filter :DefaultForAz)
         (map :SubnetId))))

(defn await-cluster-status
  "Blocks until the given cluster status matches the given status. The special
  status :deleted is used when a DB cluster does not exist. Returns cluster
  identifier."
  [target-status db-cluster-identifier]
  (let [rds (aws-client :rds)]
    (await-fn (fn []
                (let [status
                      (-> rds
                            (aws-invoke {:op :DescribeDBClusters
                                         :request {:DBClusterIdentifier
                                                   db-cluster-identifier}})
                            :DBClusters
                            first
                            :Status
                            (try+ (catch [:cognitect.aws.error/code
                                          "DBClusterNotFoundFault"] e
                                    :deleted)))]
                  (info :status status)
                  (or (= status target-status)
                      (throw+ {:type :waiting-for-cluster-status
                               :db-cluster-identifier db-cluster-identifier
                               :expected              target-status
                               :actual                status}))))
              {:timeout        (* 1000 60 20) ; yes this literally takes 20 min
               :retry-interval 5000
               :log-interval   30000
               :log-message    (str "Waiting for " db-cluster-identifier
                                    " to become " target-status)}))
  db-cluster-identifier)

(defn teardown-clusters!
  "Tears down all RDS clusters. Async."
  []
  (let [rds (aws-client :rds)
        clusters (aws-invoke rds {:op :DescribeDBClusters})]
    (->> clusters
         :DBClusters
         ; TODO: filter to tagged resources
         (map :DBClusterIdentifier)
         (mapv (fn [c]
                 (info "Tearing down DB cluster" c)
                 (aws-invoke rds {:op :DeleteDBCluster
                                  :request {:DBClusterIdentifier c
                                            :SkipFinalSnapshot true}})
                 c)))))

(defn maybe-teardown-subnet-groups!
  "Tears down all RDS subnet groups if possible. If DBs are still running, this
  will fail. Deleting DBs takes fucking ages, so we don't bother being precise
  here."
  []
  (let [rds (aws-client :rds)
        subnet-groups (aws-invoke rds {:op :DescribeDBSubnetGroups})]
    (->> subnet-groups
         :DBSubnetGroups
         ; TODO: filter to tagged resources using e.g. :DBSubnetGroupArns
         (map :DBSubnetGroupName)
         (mapv (fn [sng]
                 (info "Tearing down DB Subnet Group" sng)
                 (try+
                   (aws-invoke rds {:op :DeleteDBSubnetGroup
                                    :request {:DBSubnetGroupName sng}})
                   (catch [:cognitect.aws.error/code
                           "InvalidDBSubnetGroupStateFault"] e
                     [sng :invalid-state]))
                 [sng :deleted])))))

(defn teardown!
  "Tears down all RDS clusters and, if not in use, subnet groups."
  []
  (into (teardown-clusters!)
        (maybe-teardown-subnet-groups!)))

(defn create-subnet-group!
  "Creates Jepsen's subnet group."
  [db-subnet-group-name vpc]
  (let [rds                  (aws-client :rds)
        subnet-ids           (default-subnet-ids vpc)
        ; Create subnet group. See https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBSubnetGroup.html
        req {:DBSubnetGroupDescription "Automatically created by Jepsen"
             :DBSubnetGroupName       db-subnet-group-name
             :SubnetIds               subnet-ids
             :Tags                    [aws-tag]}
        _ (info "Creating DB Subnet Group" db-subnet-group-name)
        r (aws-invoke rds {:op :CreateDBSubnetGroup
                           :request req})]))

(defn ensure-subnet-group!
  "Ensures Jepsen's subnet group exists. Creates it if it doesn't exist. This
  doesn't check to make sure the subnet group actually reflects the vpc and
  subnet IDs we might want, but hopefully that's not a big loss."
  [db-subnet-group-name vpc]
  (try+ (aws-invoke (aws-client :rds)
                    {:op :DescribeDBSubnetGroups
                     :request {:DBSubnetGroupName db-subnet-group-name}})
        (catch [:cognitect.aws.error/code "DBSubnetGroupNotFoundFault"] e
          (create-subnet-group! db-subnet-group-name vpc))))

(defn create-public-security-group!
  "Creates the public security group for a VPC. Returns the ID of the security
  group. Does not work."
  [vpc]
  (let [ec2 (aws-client :ec2)
        _ (info "Creating security-group" public-security-group)
        r (aws-invoke ec2
                      {:op      :CreateSecurityGroup
                       :request {:GroupName public-security-group
                                 ; I've tried eight zillion ways to do this and
                                 ; I cannot for the life of me get it to stop
                                 ; complaining "The request must contain the
                                 ; parameter GroupDescription"
                                 :GroupDescription "foo"
                                 :TagSpecifications [aws-tag]
                                 :VpcId vpc
                                 }})
        _ (prn :here)
        _ (pprint r)
        r (aws-invoke ec2
                      {:op :AuthorizeSecurityGroupIngress
                       :request {}})]))

(defn ensure-public-security-group!
  "Ensures our public security group exists--one which allows traffic from
  this node's public IP. Returns the ID of the security group. Does not work."
  [vpc]
  (let [ec2 (aws-client :ec2)]
    (try+ (let [r (aws-invoke ec2
                              {:op :DescribeSecurityGroups
                               :request {:GroupNames [public-security-group]
                                         :Filters [{:Name "tag-key"
                                                    :Values [(:Key aws-tag)]}]}})]
            (pprint r))
          (catch [:cognitect.aws.error/code "InvalidGroup.NotFound"] e
            (create-public-security-group! vpc)))))

(defn describe-cluster
  "Describes the cluster with the given ID."
  [id]
  (-> (aws-invoke (aws-client :rds)
                  {:op :DescribeDBClusters
                   :request {:DBClusterIdentifier id}})
      :DBClusters
      first))

(defn create-postgres!
  "Creates a Postgres cluster. Options (see
  https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBCluster.html):

    :vpc                        The VPC to use. Defaults to the first VPC you
                                have. This VPC should have subnets in each AZ.

    :security-group-id          The id of a security group to assign.

    :db-cluster-identifier      The name of the cluster. Default
                                'jepsen-123...', where the numbers are
                                System/currentTimeMillis.

    :database-name              The database created in the cluster. Default
                                'jepsen'.

    :allocated-storage          Storage, in GB. Default 32.

    :storage-type               The storage type. Default 'gp3'.

    :iops                       Provisioned IO per second for each instance.
                                Default nil.

    :db-cluster-instance-class  The DB nodes to use. See https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.DBInstanceClass.html. Default 'db.m6id.large'.

    :db-subnet-group-name       The name of the DB subnet to create. Default
                               'jepsen-db-subnet'.

    :engine-version             The DB version to use. Default '17.4.

    :master-username            The name of the master user for the DB cluster.
                                Default 'jepsen'.

    :master-user-password       The password for the master user. Default
                                'jepsenpw'.

    :publicly-accessible        Whether to make the DB cluster publicly
                                accessible. Default true.

  Returns this option map, augmented with additional keys:

    :port                       The port to connect to the cluster

    :endpoint                   The endoint for writers
    :reader-endpoint            The endpoint for readers
  "
  [opts]
  (let [rds                  (aws-client :rds)
        vpc                  (:vpc opts (vpc-id))
        db-subnet-group-name (:db-subnet-group-name opts "jepsen-db-subnet")
        _                    (ensure-subnet-group! db-subnet-group-name vpc)
        ;_                    (ensure-public-security-group! vpc)
        ; Create DB
        db-cluster-identifier (:db-cluster-identifier
                                opts (str "jepsen-" (System/currentTimeMillis)))
        master-user-password (:master-user-password opts "jepsenpw")
        req (cond-> {:DBClusterIdentifier db-cluster-identifier
                     :Engine              "postgres"
                     :StorageType         (:storage-type opts "gp3")
                     :AllocatedStorage    (:allocated-storage opts 32)
                     :DatabaseName        (:database-name opts "jepsen")
                     :DBClusterInstanceClass (:db-cluster-instance-class
                                               opts "db.m6id.large")
                     :DBSubnetGroupName  db-subnet-group-name
                     :EngineVersion      (:engine-version opts "17.4")
                     :MasterUsername     (:master-username opts "jepsen")
                     :MasterUserPassword master-user-password
                     :PubliclyAccessible (:publicly-accessible opts true)
                     :Tags               [aws-tag]}
              (:security-group-id opts) (assoc :VpcSecurityGroupIds
                                               [(:security-group-id opts)])
              (:iops opts) (assoc :Iops (:iops opts)))
        _ (info "Creating DB cluster" db-cluster-identifier)
        c (:DBCluster (aws-invoke rds
                                  {:op :CreateDBCluster
                                   :request req}))]
    (await-cluster-status "available" (:DBClusterIdentifier c))
    (pprint c)
    (assoc opts
           :db-cluster-identifier (:DBClusterIdentifier c)
           :allocated-storage     (:AllocatedStorage c)
           :publicly-accessible   (:PubliclyAccessible c)
           :endpoint              (:Endpoint c)
           :reader-endpoint       (:ReaderEndpoint c)
           :port                  (:Port c)
           :master-username       (:MasterUsername c)
           :master-user-password  master-user-password
           :db-cluster-instance-class (:DBClusterInstanceClass c)
           :engine-version        (:EngineVersion c)
           :database-name         (:DatabaseName c)
           :iops                  (:Iops c)
           :db-subnet-group       (:DBSubnetGroup c)
           :storage-type          (:StorageType c)
           :engine                (:Engine c))))
