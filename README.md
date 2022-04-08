# Debezium - MongoDB DEMO

This project shows all configuration steps to use a Change Data Capture (CDC) approach for extracting data from MongoDB into Kafka, combined with a schema egistry.

It has been tested with the following versions:
* OpenShift 4.8
* AMQ Streams 1.8.4
* Debezium 1.7.2
* Red Hat Service Registry 2.0.5
* MongoDB Community 4.2.6

The demo uses two different projects: one for Mongo database, and the other for AMQ Streams and related components (including Service Registry)

## 1. Create working namespaces

```bash
$ oc new-project poc-mongo-dbz

$ oc new-project mongo
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

You can test your Kafka installation using Kafka producer and consumer scripts.


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

# Build Image and deploy Kafka Connect cluster (containing downloaded MongoDB connector plugin and converters)
$ oc apply -f kafka-connect-single-node-with-build.yaml -n poc-mongo-dbz
```

### Review generated image

Inside the new Kafka Connect POD you can find the included connector and converters here:

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

Create and configure a new project for deploying Mongo

```bash
# New Mongo namespace
$ oc new-project mongodb

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

```bash
# Optional: forward port for remote access from your localhost using your favourite client
$ oc port-forward example-mongodb-0 27017:27017 -n mongodb

```

### 6.4 Create Database and collections

1. Connect to Mongo using:
   * Auth database: admin
   * SCRAM-SHA-256 auth mechanism
   * user: myuser
   * password: mypassword
2. Create a new database with name ```demodb```
3. Create a collection ```mycollection``` and populate with documents. Example document:

```json
{
    "field1" : "mydocument1",
    "field2" : "test value",
    "myarray" : [ 
        {
            "subfield1" : "value 1-1",
            "subfield2" : "value 1-2"
        },
        {
            "subfield1" : "value 2-1",
            "subfield2" : "value 2-2"
        }        
    ],
    "field3" : UUID("4a0e22f4-4687-451f-9738-60fd09eb0bd7")
}
```


## 7. Deploy Debezium Kafka Connector

The last step is to deploy a new Kafka Connector configured to start with Mongo Debezium plugin.

The provided file example is configured as follows:

* It checks ```demodb`` database ONLY.
* It will register the schema in Service Registry using AVRO
* CDC events are serialized to Kafka topics using AVRO

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

Also, the schemas for *keys* and *values* has been created in Service Registry:

### Artifacts:

![Artifacts](/images/apicurio-schemas-1.png)

### Artifact detail info:

![Artifact-details](/images/apicurio-schemas-2.png)

### Artifact detail content:

![Artifact-content](/images/apicurio-schemas-3.png)
