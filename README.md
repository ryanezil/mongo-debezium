# Debezium - MongoDB DEMO

This project shows all configuration steps to use a Change Data Capture (CDC) approach for extracting data from MongoDB into Kafka, combined with a schema egistry.

It has been tested with the following versions:
* OpenShift 4.8
* AMQ Streams 1.8.4
* Debezium 1.7.2
* Red Hat Service Registry 2.0.5
* MongoDB Community 4.2.6

The demo uses two different projects: one for Mongo database, and the other one for AMQ Streams and related components (including Service Registry).

## 1. Create working namespaces

```bash
$ oc new-project mongodb

$ oc new-project poc-mongo-dbz
```

## 2. Install Operators

The following subscriptions are created to deploy the operators:

* AMQ Streams operator
* Service Registry operator

```bash
$ oc create -f operator-group.yaml -n poc-mongo-dbz

$ oc create -f service-registry-operator.yaml -n poc-mongo-dbz

$ oc create -f amq-streams-operator.yaml -n poc-mongo-dbz
```


## 3. Install AMQ Streams

This is a **single-node** cluster (one Zookeeper and one Kafka) for testing purposes. It's intended for the demo and minimize resources usage.


```bash
# Create cluster
$ oc apply -f kafka-single-node.yaml -n poc-mongo-dbz

# We create a superuser for all tasks (to ease configuration)
$ oc apply -f superuser.yaml -n poc-mongo-dbz
```

### OPTIONAL: test Kafka Broker

You can test your Kafka installation by using Kafka producer and consumer scripts.


```bash
# Get the superser password:

SUPERUSER_PASS=$(oc get secret superuser --template='{{ .data.password }}' | base64 -d)
```

```bash
# Send messages (press CTRL+C to finish)

oc run kafka-producer -ti --image=registry.redhat.io/amq7/amq-streams-kafka-28-rhel8:1.8.4-2 --rm=true --restart=Never -- /bin/bash -c "cat >/tmp/producer.properties <<EOF 
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=superuser password=$SUPERUSER_PASS;
EOF
bin/kafka-console-producer.sh --broker-list single-node-cluster-kafka-bootstrap:9092 --topic my.test.topic --producer.config=/tmp/producer.properties
"
```

```bash
# Consume messages sent in previous step:

oc run kafka-consumer -ti --image=registry.redhat.io/amq7/amq-streams-kafka-28-rhel8:1.8.4-2 --rm=true --restart=Never -- /bin/bash -c "cat >/tmp/consumer.properties <<EOF 
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=superuser password=$SUPERUSER_PASS;
EOF
bin/kafka-console-consumer.sh --bootstrap-server single-node-cluster-kafka-bootstrap:9092 --topic my.test.topic --from-beginning --consumer.config=/tmp/consumer.properties --group sample-group
"
```

## 4. Deploy Service Registry

Deploy Service Registry with data storage in AMQ Streams.

The deployment is using SCRAM security and the same 'superuser' created when AMQ  Streams was configured (this is a demo and we want to avoid creating a new user and ACLs).

Kafka default topic name for Service Registry is ```kafkasql-journal``` and it is created automatically by SR (you can use different name if needed). 

Further info: [Configuring Kafka storage with SCRAM security](https://access.redhat.com/documentation/en-us/red_hat_integration/2022.q1/html-single/installing_and_deploying_service_registry_on_openshift/index#registry-persistence-kafkasql-scram)


```bash
$ oc create -f service-registry.yaml -n poc-mongo-dbz
```

Once deployed, the operator will expose a route to access Apicurio web console. The console is configured to be ```read-only``` (you can change it by editing the Custom Resource).

## 5. Deploy Debezium

Debezium is deployed on top of Kafka Connect. You need to include all Debezium required libraries and dependencies in a custom Kafka Connect image, in order to deploy and run your CDC connector.

Beginning with Debezium 1.7, the preferred method for deploying a Debezium connector is to use AMQ Streams to build a Kafka Connect container image that includes the connector plug-in.

* See documentation DBZ 1.7 for MongoDB: [Deployment of Debezium MongoDB connectors](https://access.redhat.com/documentation/en-us/red_hat_integration/2022.q1/html-single/debezium_user_guide/index#deployment-of-debezium-mongodb-connectors)

### Deploy Kafka Connect customized with the Plugin Connector

```bash
# This secret contains MongoDB user credentials for authentication and is mounted when Kafka Connector is running
$ oc create -f myuser-credentials-secret.yaml -n poc-mongo-dbz

# The target imagestream for the customized image
$ oc create -f kafka-connect-is.yaml -n poc-mongo-dbz

# Build Image and deploy Kafka Connect cluster (the image will contain downloaded MongoDB connector plugin and the converters)
$ oc apply -f kafka-connect-single-node-with-build.yaml -n poc-mongo-dbz
```

### Review generated image

Open a remote shell to the new Kafka Connect POD. Inside you can find the included connector and converters here:

```bash
$ ls -la /opt/kafka/plugins/

total 16
drwxr-xr-x. 4 root root 4096 Apr  6 11:42 .
drwxr-xr-x. 8 root root 4096 Apr  6 11:42 ..
drwxr-xr-x. 3 root root 4096 Apr  6 11:42 debezium-connector-mongodb
drwxr-xr-x. 3 root root 4096 Apr  6 11:42 debezium-converters
```

```bash
$ ls -la /opt/kafka/plugins/debezium-connector-mongodb/06c38436/debezium-connector-mongodb/
total 6416
drwxr-xr-x. 2 root root    4096 Jan  5 07:36 .
drwxr-xr-x. 3 root root    4096 Apr  6 11:42 ..
-rw-r--r--. 1 root root  308966 Jan  5 07:32 CHANGELOG.md
-rw-r--r--. 1 root root   19228 Jan  5 07:32 CONTRIBUTE.md
-rw-r--r--. 1 root root    4981 Jan  5 07:32 COPYRIGHT.txt
-rw-r--r--. 1 root root  129157 Jan  5 07:31 LICENSE-3rd-PARTIES.txt
-rw-r--r--. 1 root root   11357 Jan  5 07:31 LICENSE.txt
-rw-r--r--. 1 root root   15286 Jan  5 07:32 README.md
-rw-r--r--. 1 root root   19520 Jan  5 07:32 README_JA.md
-rw-r--r--. 1 root root   13114 Jan  5 07:32 README_ZH.md
-rw-r--r--. 1 root root  499167 Jan  5 07:36 bson-4.2.1.redhat-00001.jar
-rw-r--r--. 1 root root   21344 Jan  5 07:34 debezium-api-1.7.2.Final-redhat-00003.jar
-rw-r--r--. 1 root root  167436 Jan  5 07:36 debezium-connector-mongodb-1.7.2.Final-redhat-00003.jar
-rw-r--r--. 1 root root  887025 Jan  5 07:34 debezium-core-1.7.2.Final-redhat-00003.jar
-rw-r--r--. 1 root root    4024 Jan  5 07:34 failureaccess-1.0.1.redhat-00001.jar
-rw-r--r--. 1 root root 2851314 Jan  5 07:34 guava-30.0.0.jre-redhat-00002.jar
-rw-r--r--. 1 root root 1444850 Jan  5 07:36 mongodb-driver-core-4.2.1.redhat-00001.jar
-rw-r--r--. 1 root root  137413 Jan  5 07:36 mongodb-driver-sync-4.2.1.redhat-00001.jar
```

## 6. Install MongoDB Community

The next steps have been defined using the documentation from the **Community Kubernetes Operator** repository. See [here](https://github.com/mongodb/mongodb-kubernetes-operator/blob/master/docs/install-upgrade.md#procedure).


### 6.1. Clone Mongodb Kubernetes Operator repository

Clone the repo:

```bash
$ git clone https://github.com/mongodb/mongodb-kubernetes-operator.git
```

In order to deploy MongoDb operator on OpenShift, the deployment manifest has to be modified: the main change is the new variable ```MANAGED_SECURITY_CONTEXT``` for deploying operator in OpenShift (see [Deploy Replica Sets on OpenShift](https://github.com/mongodb/mongodb-kubernetes-operator/blob/master/docs/deploy-configure.md#deploy-replica-sets-on-openshift)).

Also, some options are removed.


Edit the deployment manifest file: ```./mongodb-kubernetes-operator/config/manager/manager.yaml```

Add the new env-var:
```yaml
- name: MANAGED_SECURITY_CONTEXT
  value: 'true'
```

Comment or remove the lines:
```yaml
#    securityContext:
#      readOnlyRootFilesystem: true
#      runAsUser: 2000
#      allowPrivilegeEscalation: false
#  securityContext:
#    seccompProfile:
#      type: RuntimeDefault
```

The resulting file looks like: 

```yaml
    :
    :
    :
        - name: MANAGED_SECURITY_CONTEXT
          value: 'true'
        - name: OPERATOR_NAME
          value: mongodb-kubernetes-operator
        - name: AGENT_IMAGE
          value: quay.io/mongodb/mongodb-agent:11.12.0.7388-1
        - name: VERSION_UPGRADE_HOOK_IMAGE
          value: quay.io/mongodb/mongodb-kubernetes-operator-version-upgrade-post-start-hook:1.0.4
        - name: READINESS_PROBE_IMAGE
          value: quay.io/mongodb/mongodb-kubernetes-readinessprobe:1.0.8
        - name: MONGODB_IMAGE
          value: mongo
        - name: MONGODB_REPO_URL
          value: docker.io
        image: quay.io/mongodb/mongodb-kubernetes-operator:0.7.3
        imagePullPolicy: Always
        name: mongodb-kubernetes-operator
        resources:
          limits:
            cpu: 1100m
            memory: 1Gi
          requests:
            cpu: 500m
            memory: 200Mi
#        securityContext:
#          readOnlyRootFilesystem: true
#          runAsUser: 2000
#          allowPrivilegeEscalation: false
#      securityContext:
#        seccompProfile:
#          type: RuntimeDefault
      serviceAccountName: mongodb-kubernetes-operator
```

### 6.2. Deploy Mongo Operator

Create and configure the project ```mongodb``` for deploying Mongo. Remember it was created at the beginning.

```bash
# INSTALL CRDs
$ oc apply -f mongodb-kubernetes-operator/config/crd/bases/mongodbcommunity.mongodb.com_mongodbcommunity.yaml -n mongodb

customresourcedefinition.apiextensions.k8s.io/mongodbcommunity.mongodbcommunity.mongodb.com configured

# Config RBAC
$ oc apply -k mongodb-kubernetes-operator/config/rbac/ -n mongodb

serviceaccount/mongodb-database created
serviceaccount/mongodb-kubernetes-operator created
role.rbac.authorization.k8s.io/mongodb-database created
role.rbac.authorization.k8s.io/mongodb-kubernetes-operator created
rolebinding.rbac.authorization.k8s.io/mongodb-database created
rolebinding.rbac.authorization.k8s.io/mongodb-kubernetes-operator created
```

Deploy Mongo operator:

```bash
# Create the Mongo deployment using the modified file in step 6.1:
$ oc create -f mongodb-kubernetes-operator/config/manager/manager.yaml -n mongodb
```

When the operator is running you should see something like this:

```bash
$ oc get pods
NAME                                           READY   STATUS    RESTARTS   AGE
mongodb-kubernetes-operator-5fb6fb56fd-x2tbg   1/1     Running   0          2m23s
```

### 6.3. Deploy Mongo Database

We are deploying a replicaset with one single member. The Debezium connector does not support satandalone servers. See [Supported MongoDB topologies](https://debezium.io/documentation/reference/1.9/connectors/mongodb.html#supported-mongodb-topologies).


```bash
# Create the UserSecret (the user/password for connecting to Mongo)
$ oc create -f myuser-credentials-secret.yaml -n mongodb

# Deploy MongoDB replicaset
$ oc create -f deploy-mongodb_cr.yaml -n mongodb

mongodbcommunity.mongodbcommunity.mongodb.com/example-mongodb created
```

### 6.4 Run CamelK producer

A demo producer has been prepared to insert documents into MongoDB. The producer [MongoDocInserter.java](./camelk/MongoDocInserter.java) is implemented using [CamelK](https://access.redhat.com/documentation/en-us/red_hat_integration/2022.q2/html-single/getting_started_with_camel_k/index).

It will create a new database with name ```demodb``` and then it will insert sequential documents into the collection ```mycollection``` every 10 seconds.

Example: 
```json
{
    "username" : "Demo Name #0"
}
```


**NOTE**: [CamelK CLI - kamel](https://camel.apache.org/camel-k/1.9.x/cli/cli.html) must be installed in order to exec the following commands.

Install CamelK operator using Kamel:

```bash
$ kamel install --maven-repository https://maven.repository.redhat.com/ga/
```

Deploy the producer CamelK route

```bash
$ kamel run ./camelk/MongoDocInserter.java -p quarkus.mongodb.connection-string=mongodb://myuser:mypassword@example-openshift-mongodb-svc.mongodb.svc.cluster.local:27017/admin
```

Inspect output logs:

```bash
$ kamel log mongo-doc-inserter

  :
  :
  :

[1] 2022-06-08 14:36:13,965 INFO  [org.apa.cam.com.mon.MongoDbEndpoint] (main) Initialising MongoDb endpoint: mongodb://camelMongoClient?collection=mycollection&database=demodb&operation=insert
[1] 2022-06-08 14:36:13,988 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main) Routes startup summary (total:1 started:1)
[1] 2022-06-08 14:36:13,989 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main)     Started route1 (timer://tick)
[1] 2022-06-08 14:36:13,989 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main) Apache Camel 3.11.5.fuse-800012-redhat-00004 (camel-1) started in 101ms (build:0ms init:70ms start:31ms)
[1] 2022-06-08 14:36:14,002 INFO  [io.quarkus] (main) camel-k-integration 1.6.6 on JVM (powered by Quarkus 2.2.5.Final-redhat-00010) started in 4.420s.
[1] 2022-06-08 14:36:14,003 INFO  [io.quarkus] (main) Profile prod activated.
[1] 2022-06-08 14:36:14,003 INFO  [io.quarkus] (main) Installed features: [camel-bean, camel-core, camel-java-joor-dsl, camel-k-core, camel-k-runtime, camel-log, camel-mongodb, camel-timer, cdi, mongodb-client, smallrye-context-propagation]
[1] 2022-06-08 14:36:14,989 INFO  [info] (Camel (camel-1) thread #0 - timer://tick) Exchange[ExchangePattern: InOnly, BodyType: String, Body: {"username":"Demo Name #0"}]
[1] 2022-06-08 14:36:15,233 INFO  [org.mon.dri.connection] (Camel (camel-1) thread #0 - timer://tick) Opened connection [connectionId{localValue:5, serverValue:1362}] to example-openshift-mongodb-svc.mongodb.svc.cluster.local:27017
[1] 2022-06-08 14:36:15,250 INFO  [route1] (Camel (camel-1) thread #0 - timer://tick) Document inserted!
[1] 2022-06-08 14:36:24,983 INFO  [info] (Camel (camel-1) thread #0 - timer://tick) Exchange[ExchangePattern: InOnly, BodyType: String, Body: {"username":"Demo Name #1"}]
[1] 2022-06-08 14:36:24,986 INFO  [route1] (Camel (camel-1) thread #0 - timer://tick) Document inserted!
```

### 6.5 [Optional]: Connect to MongoDB

You can connect to MongoDB by using your favourite client. If you want to do it, a very simple way is to forward a local port to the remote port in the cluster and then configure yor connection details.

```bash
$ oc port-forward example-mongodb-0 27017:27017 -n mongodb
```

Connect to Mongo using:
 * locahost:27017
 * Auth database: admin
 * SCRAM-SHA-256 auth mechanism
 * user: myuser
 * password: mypassword


## 7. Deploy Debezium Kafka Connector

The last step is to deploy a new Kafka Connector configured to start with Mongo Debezium plugin.

The provided connector example ([dbz-mongodb-connector-sr.yaml](./dbz-mongodb-connector-sr.yaml))is configured as follows:

* It checks ```demodb``` database ONLY.
* It will register the schema in Service Registry using AVRO.
* CDC events are serialized to Kafka topics using AVRO.

```bash
# Deploy Debezium Connector
$ oc apply -f dbz-mongodb-connector-sr.yaml -n poc-mongo-dbz
```
The Change Data Capture process starts working and replicating events from the collections into the topic.

A new topic has been created for the previosuly created collection:

```bash
$ oc get kt mongo-dbz-demo.demodb.mycollection
NAME                                 CLUSTER               PARTITIONS   REPLICATION FACTOR   READY
mongo-dbz-demo.demodb.mycollection   single-node-cluster   1            1                    True
```

Once the connector is running, it will detect every new document inserted into MongoDB and will insert an event into the new topic ```mongo-dbz-demo.demodb.mycollection```.


### Check the inserted events

You can use the script ```kafka-console-consumer.sh``` to read the inserted events from the topic:


```bash
# Get the superser password:
$ SUPERUSER_PASS=$(oc get secret superuser --template='{{ .data.password }}' -n poc-mongo-dbz | base64 -d)

# Start consuming messages from the Kafka topic:

$ oc run kafka-consumer -ti --image=registry.redhat.io/amq7/amq-streams-kafka-28-rhel8:1.8.4-2 --rm=true --restart=Never -- /bin/bash -c "cat >/tmp/consumer.properties <<EOF 
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username=superuser password=$SUPERUSER_PASS;
EOF
bin/kafka-console-consumer.sh --bootstrap-server single-node-cluster-kafka-bootstrap:9092 --topic mongo-dbz-demo.demodb.mycollection --from-beginning --consumer.config=/tmp/consumer.properties --group sample-group
"
```

The console output will show entries like this (remember the messages are serialized and stored in the **binary** format AVRO):

```bash
�{"_id": {"$oid": "62a0beb3765e711c2abaa8cf"},"username": "Demo Name #56"}01.7.2.Final-redhat-00003mongodbmongo-dbz-demo�Ǿ�`
false
     demodbexample-mongodbmycollectionc�Ǿ�`
```


## 8. Check Service Registry

The schemas for *keys* and *values* has been created in Service Registry by Debezium

You can access to the Apicurio Console retrieving the URL from the route. Example:

```bash
oc get route dbz-apicurioregistry-kafkasql-ingress-z8slb -n poc-mongo-dbz --template='{{ .spec.host }}'
```

> **NOTE** the route name might change.

### Artifacts:

![Artifacts](/images/apicurio-schemas-1.png)

### Artifact detail info:

![Artifact-details](/images/apicurio-schemas-2.png)

### Artifact detail content:

![Artifact-content](/images/apicurio-schemas-3.png)
