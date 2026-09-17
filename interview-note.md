Introduction

Java inheritance
It establishes an is-a relationship between classes. The child class inherits all methods and fields from the parent class.


Bean initialize flow: it is initi

OOP
- Encapsulation: it defines the scopes of variables and methods through access modifiers
- Inheritance: it establishes an is-a relationship between classes. The child class inherits all methods and fields from the parent class.
- Polymorphism: it allows objects of different types to be treated as instances of the same type through a common interface
- Abstraction: it hides the complex implementation details and shows only the essential features of an object

Inheritance vs composition?
- Inheritance: "is-a" relationship (e.g., Dog is-an Animal), it is tight coupling, and can not be changed at runtime
- Composition: "has-a" relationship (e.g., Dog has-a Tail), it is loose coupling, and can be changed at runtime

Abstract class vs interface?
- abstract is-a relationship, the class can have constructor, fields, and concrete methods. Moreover, it has internal state
- interface defines what the class can do, and it can have default methods. However, it doesn't have constructor, fields, or internal state

Functional interface? How many types?
- it only has one abstract method. There are 4 types which are
- Predicate: receive an arg and return a boolean
- Function: receive an arg and return a transform of it
- Consummer: receive an arg and return nothing
- Supplier: receive nothing and return a result

Lambda function?
- A lambda function is an anonymous function that can be used to implement a functional interface.

Stream API: how many types of operations?
- It has two types which are intermediate and terminal operations
- Intermediate operations is a chain that returns another stream like map, filter
- Terminal operations is the end of the chain and return a result which is not the stream like collect to list

Explain these keywords: final, volatile, synchronized, transient, native
- final defines immutable reference or method, it can use in variable, method, and classes.
- volatile ensures visibility of changes across threads but does not provide atomicity
- synchronized ensures thread safety by preventing multiple threads from executing the same code block at the same time
- transient stores the value in memory but not in the serialized object, when it is deserialized, it will be initialized to its default value.
- native defines methods that requires implementation from native code

What is a race condition and a visibility bug in concurrency? 
- Race condition: bugs happend because of multiple thread modified the shared state at the same time, and we can't predict the results.
- Visibility bug: bugs happend because of the value of shared variable is not visible to other threads.

synchronized compared to ReentrantLock?
- synchronized is a keyword that can be used to ensure thread-safety, the lock will be automatically lock when executing the method or block, and release when the method or block is finished.
- ReentrantLock is a class that can be used to ensure thread-safety, the lock and release must be manually called. Moreover, in ReetrantLock, we have more controls
such as tryLock, tryLock with timeout, interuptiple lock, and fair or unfair lock.

With concurrent threads accessing shared mutable state data, how to solve it with ReentrantLock? ReentrantLock deep dive.


Collections in Java: List vs Set vs Map
Collections is represent for the architecture of collection objects
List and Set belongs to Collection interface
List is a continous array of elements, it can have duplicate elements
Set is a collection of unique elements

Map is a key pair value architecture

ArrayList vs LinkedList, internally?
ArrayList is a dynamic array, however, internally, it uses fixed continous array, when it needs to expand, it will copy the array to a new location with larger size
LinkedList includes many nodes and using pointer to connect each node, the location of each node is not continous

Complexity when adding at the end, in the middle, or at the beginning? Worst cases?

HashMap: explain these cases; which one is correct? why?

1. key1.equals(key2) = *true*, key1.hashcode() ==  key1.hashcode() is *true*
2. key1.equals(key2) = *true*, key1.hashcode() ==  key1.hashcode() is *false*
3. key1.equals(key2) = *false*, key1.hashcode() ==  key1.hashcode() is *true*
4. key1.equals(key2) = *false*, key1.hashcode() ==  key1.hashcode() is *false*

HashMap internally?
- HashMap internally is an array of buckets, each bucket is a linked list or red-black tree of entity based on some conditions which is treeify threshold, and min treeify capacity

Spring vs SpringBoot?
- Spring is an open-source framework, built modular enterprise java application, built around IOC, DI, AOP and manage object lifecycle.
- Spring Boot is an extension built on top of Spring that eliminates boilerplate configuration and streamline application deployment via convention over configuration 
How does Spring Boot handle auto-configuration? Compare to old-school Spring?
- Spring boot handle auto-configuration by examining the classpath, application properties, envrionment, and existing bean, then registering the bean that match the condition.
Eg, if Spring MVC and Tomcat are presents, it will configure web application and embedded server.
Auto-configuration is non-invasive, if the application already defines their own beans, Boot usally backs off
because there are many default class using the annotation ConditionalOnMissingBean
There are some annotation about Conditional like
ConditionalOnClass: that configure some things when it has on the classpath
ConditionalOnMissingBean: configure the bean if the application doesn't have one
ConditionalOnProperty: configure the bean if the property is set
ConditionalOnWebApplication: configure the bean if it is a web application
ConditionalOnBean: apply only when there is another bean exists 
Compare to the old spring, it has to declare explicit all beans, no embedded server, no production support


@Transactional in Spring? explain propagation = Propagation.REQUIRED? propagation = Propagation.REQUIRED_NEW?
- Transactional is an annotation that bound the method or class with transactional context that ensures
all db operations succeed or fails as one unit
proparation:
- REQUIRED: if there is aready a transaction, join it, otherwise create a new one
- REQUIRED_NEW: create a new transaction, suspend the current one if it exists
isolation on Transactional:
- READ_UNCOMMITTED: it allows dirty read, repeatable read, and phantom read
- READ_COMMITED: it allows repeatable read, and phantom read
- REPEATABLE_READ: it allows phantom read
- SERIALIZABLE: it prevents all. Moreover in Serializable isolation, it also prevents Write Skew
when ever update a record, it will check the dependencies of that record. However, it has trade off like reducing throughput and produce serialize failure that requires retry on application

@Transactional self-invocation? How to fix it?
- self-invocation occurs when a method in the same class calls another methos using this. Transactional is not working because it doesn't go through the proxy of Spring.
- To fix it, we need to separate transactional to other service, or using Lazy annotation to initialized Order service

Explain ACID? Isolation levels? Dirty read, repeatable read, phantom read?
- Atomicity: ensure all operations are completed successfully, otherwise, it will be rolled back
- Consistency: ensure the database is in a consistent state before and after the transaction
- Isolation: ensure that concurrent transactions do not interfere with each other
- Durability: ensure that once a transaction is committed, it will remain so, even in the event of a system failure

- Dirty read: reading uncommitted data from another transaction
- Repeatable read: reading the same data multiple times and getting the same result
- Phantom read: reading the same query multiple times and getting different results because of other transactions committed the transaction before reading again.

Index? How are indexes stored in a DB? Tree or map? How does it improve search queries? trade off?
- Index is a data structure that improves the speed of data retrieval operations on data table. It's similar to book's table of contents, so instead of scanning the entire table, it can jump directly to
the row.
- index is stored on disk, and it uses B tree to oragnize the data, in the root page and internal page will guide the search, leaf page contains the actual data.
- Instead of scanning entire table which cost O(n), now it is tree traverse so it is O(log n)
- However, it also has the trade-off such as:
- Slow write because new row need to add it to tree, sort, and rebalance the tree
- Low-value index such as status column or boolean column
- Additional storage space
- Incorrect composite order index
For the index types:
- Unique index
- Composite index
- Parital Index
- Covering index


Have you ever designed DB tables, relationships,  … for an app? How did you approach it?
To design the db table, I would start with the business requirements, then identify the entities and their relationships, and finally design the tables and their columns.

How to design REST Api?
To design REST Api, I would start with the business requirements, then follows consistent naming convention with noun-based URL and HTTP methods. Now, I then explicit defines the request and response contract
, status code, validation, and error format. After all, we can consider about pagination, idempotency, security, backward compability and document.

Kafka basic concepts?
Kafka is a distributed streaming platform, it appends only and uses the Pull Model that allow consumer to pull and read messages at their pace.

Kafka delivery semantics types? explain?
- At most once: message are delivered zero or one time, the message can be lost.
- At least once: message are delivered one or more times, the message can be duplicated, we have to handle idempotency in application.
- Exactly once: message are delivered exactly one time

How does Kafka keep messages in order? Idempotent in Kafka?
Kafka keep message in order based on the key, the same key will go into the same partition, then the order is preserved. It only ensures the order in the same partition not the global.
Idempotent in kafka has two concepts:
- Producer idempotency: based on the producer id an sequence number to recognize the duplicate message in broker. However, it doesn't guarantee when intended message with the same event.
- Consumer idempotency: the application may crash before commit offset to kafka, so it allows to read the message again, for this one, we have to handle idempotency in the application and store the idempotency key\
to database with unique constrant.

Explain idempotency in REST?
- Idempotency in REST that means sending the same logical requests multiple times but the last result is the same with sending once.

Which method should be idempotent?
- GET, DELETE, PUT, 
How do you define an idempotency key?

Use a GitHub account to review a PR in 30 mins: 10 files from Controller to DTO, service, entities, repositories, exceptions. There are some bugs: must use @Transactional; race condition bug; double for currency... Explain while adding comments to the PR.

Do you have any questions for us?
access modifiers
overload vs override

final
transient
volatile
synchronize
native

@Transactional 
@Transactional self invoke, how to fix?

JVM internal storage?

Garbage collection
How to know which object is unreachable?

ReentrantLock? fair and no fair? real case problem of no fair?

Sping Boot auto configuration how to include a jar that already has its Bean?

Bean Scope?

Excersie
List<String> s = List.of("a", "b", "c");

for(String item : s) {
    if ("a".equals(item)) {
        s.remove(item);
    }
}
Is it has problem? and why?

How to identify the reason of increase memory?
Heap dump, thread dump?
How to define a list that prevents add or remove?


- Clean up and reclaim the space on heap, it will clean unreachable object from GC Root
GC root includes:
- local variable on active thread
- Active thread
- static reference
- JNI reference
- JVM internal ref

functional interface
default methods
How to start a thread in java
virtual thread vs the regular thread
thread synchronize (race condition)
spring boot
bean scopes
Kafka message route
DB transaction
ACID
transaction isolation leves