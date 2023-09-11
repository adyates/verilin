# Design Writeup

Broken up into separate sections based on the numbers, with a bit of a preamble of sorts.

The design topology can be viewed below or [as a PDF here](Hello%20Architecture.pdf):

![Architecture Diagram](Hello%20Architecture.jpg "Architecture diagram")

## Clarifications

For posterity, I'll add in the response to my initial battery of questiosn here:

```
Helloworld LLC has the necessary rights / or customer acceptance to process the necessary data. It will be customer and
HelloWorld LLC owned data. Its an Analysis product with a sensor to collect data and a Platform to analyse it.

Its a multi-tenant / SaaS

Req. 4: is about all the data that has been collected and is now stored in the platform. So it should run on the
internal data storage. It’s not customer facing.

Req. 5: Customers should be able to view results.

Req. 6: Customer should be able to view their raw data and run experiments / analysis tasks on their own.
```

## Assumptions and Whatnot

There's a few things going for this that I should state up front so parsing the design below
is a bit easier.  Nothing too out there, but when you've seen enough variations on a theme,
you tend to skew towards being explicit about assumptions and uses so others don't get the
wrong idea.

The main assumptions are these:
- Hello World LLC is funded but not profitable: Since it's a small company launching a new product, the assumption is 
  the main resource it has is access to expendable capital but neither raw engineering bandwidth nor the luxury of time.
  This leads to a world where risks are somewhat minimized because failure could lead to failure of the company itself.
- _Infrastructure cost is generally not an issue_: Most of the time this is a given, but sometimes people
  assume home-grown is the better option.  In my experience, NIH projects swap low-order platform
  costs for higher-order engineering salaries, which are great for managerial promotions but terrible
  for cost control, operational overhead, and general maintainability.  There's one exception, but it's noted below.
- _Baseline security is a given_: For simplicity in discussion and diagrams, certain security practices
  are going to be assumed throughout.  Namely:
  - Secure connections: Not just for APIs (HTTPS) but also for direct file transfer (SFTP)internal
  - Network segregation: In short, insulating network topology (e.g. AWS security groups, LBs with SSL termination). It
    should be assumed that, based on the topology diagram, if an arrow is not explicitly drawn between two blocks it has
    no ability to connect to that resource.
  - Data at rest: All file buckets are assumed to be configured for at-rest encryption.  External key management
    is a separate thing and will be mentioned later since the mechanics are more than "Set the right toggle".
- _Sensor as Sole Collection Interface_: As the product is described as a Sensor + Platform, for the sake of simplicity
  the sensor will be considered as the sole data generation input.  This simplifies considerations such as customers
  sending unsupported data formats, multiple authentication schemes, and general sanity.
- _Sensors are magically made and uniquely identified_: Although it could be fun for another day, we are not going to 
  worry about device manufacturing, ID generation, or other forms of operation.
- _Sensors data is genuine_: As far as I'm aware, if an attacker owns a device nothing is guaranteed.  Therefore, the
  assumption is the device is always sending legitimate data and has not been compromised / we are not trying to
  remotely defeat malicious sends.
- _Business Intelligence Use Case_: Analytical products frequently have to make decisions on how quickly a response can 
  be given. The expectations for offline analysis (Business Analysts) vs real-time threat response (SOC operators) are
  quite different from each other and change the architecture considerably.  For the sake of simplicity, the BI use case
  is assumed to be the primary case.  That said, some real-time hooks in the architecture are noted.   
- _Cloud Services are interchangeable_: With certain exceptions (e.g. Google Firestore, AWS Braket), most of the
  services are generally interchangeable with each other (allowing for some effort between API transitions and specific
  limits) or self-hosted OSS alternatives.  We'll use AWS for simplicity of design, but nothing incredibly niche
  requiring AWS specifically.
- _Server-side sanitization_: Although the devices could sanitize their own data via redaction quite easily, it's not
  known at this time if the analysis would be better served by a complete redaction or by a two-way anonymous ID.  As a
  result, the design will use server-side methods to allow for the two-way mapping. 
- _Internal Developers have full access_: Unless otherwise noted, engineers generally end up with access to everything
  in small companies.  It aids in velocity and doesn't matter as much until certain large-scale customers get access /
  specific compliance requirements need to be met.

# Responses

## What challenges do you see in these requirements? 

Definitely not an exhaustive list, but these are generally the topics I'd consider as unique to the product at hand.
For simplicity, I'm minimizing the normal technical considerations for multi-tenant SaaS as they will primarily exist
as decisions made in the design potion, opting to instead include some of the more high-level business questions which
would motivate internal strategy and non-technical decisions. 

1. **Sensitivity of personal data**: Since the sensor is collecting personal data (otherwise Req 2 doesn't need
   consideration), the next open question is if any of that information would be considered sensitive under GDPR.
   Although there are clearly defined grammars for identifying most PII (e.g. phone numbers, email addresses), the same
   might not be said for biometric data if the sensor is being used in support of an access control system.
2. **Data Lifecycle/Handling**: Although we can assume that HW and any customers are considered 'joint controllers' for
   the purposes of GDPR, the question of how to be a responsible steward of data still matters, especially as we will be
   performing a lot of processing on a given record for both live feature support and product development (e.g. pulling
   sufficiently anonymized test cases from real-world examples).
2. **Results latency**: Even though we aren't necessarily designing for real-time, certain decisions would introduce 
   processing latency for the sake of simplicity.  This is especially true if the internal services are off the     
   shelf and not hand-rolled everywhere.
3. **Operational overhead**: Small teams building from scratch need to be judicious with how their time is used.  Operational
   overhead (e.g. server maintenance, database optimizations, network configurations).  Maximizing value-generating work
   is vitally important for any product in the market.  This skews certain solutions to off-the-shelf technology as well
   as development strategies that can be easily tested and repeated.
4. **Qualitative regression prevention**: Although most behavior is deterministic and easily verified, the feature
   that may be hardest to incrementally improve is the anomaly detection, as identical inputs do not necessarily
   guarantee identical results.  Although it's a pretty well known problem, I consider ML development methods to be a
   challenge in their own right, given the nature of data set preparation and validation that needs to be performed in
   such systems.
5. **Customer Querying**: Generally you don't want customers to be able to use the exact underlying query language (e.g.
   raw SQL strings, even if sanitized) against your internal databases as it could expose a number of unintentional 
   attacks that are only as defensible as your sanitization and RBAC talents allow.  However, making a language/API both
   expressive and simple enough for customer use is similarly challenging when you include the UX point of view
   (in order to handle Req 4.5).  If that's the pathway we want to develop (in either way), that could be fine, but the
   proposed solution will bypass this particular concern for a different strategy that isn't as friendly but allows more
   freedom.

## Infrastructure Design

Most of the major points are in the topology diagram, color coded based on primary pathway.

### Device Data (Teal & Yellow)

Given that the sensor is the sole source of data generation, but has two forms, there are likely two types of data
implied by the format:

- Files (Teal): Basic file transfer, regardless of format and size, would be handled via dedicated servers that proxy long-term
  storage options.  At a minimum, the devices should be able to SFTP to a secure server as a dropbox or sorts, but if
  needed you could deploy a web service that allows for an multipart upload as well.    
- JSON (Yellow): Most monitoring systems use JSON as a form of recorded measurement of quantitative data points
  (e.g. system telemetry), so this may actually be a much higher throughput than the file use case.

Although the files are expected to be large, the number of files per device on a daily basis is likely not that large
(in the tens, likely not 100s, assuming daily log rotation).  On the other hand, a single telemetry event recorded once
per minute would be 1440 events, which behaves much more like a batched event stream.  Although it's possible to support
both workflows on the same application code, separating the File and JSON clusters would make handling production issues
easier by separating the telemetry and preventing issues with one workflow from affecting the other.  Especially as the
number of servers needed to handle only file transfer should grow much slower than JSON events, at scale (Files could
probably be handled by only basic redundant servers, but JSON handling could require actual scaling).

Data processing and sanitization would be handled similarly, with separate workflows for each type.  Individual JSON
events could be handled very simply using modern serverless infrastructure (single or batched).  However, since many
serverless function architectures have time/space limitations imposed upon them, it may make more sense to process the
larger files as they finish uploading by using a dedicated worker pool.

Both flows would end in an S3-based data lake, with the post-bucket pathway segregated by the ID of the device and the
class of data processed (e.g. /prod/1234-acde-5431/apacheLog) or any similar schema.  

### Analysis Infrastructure (Orange)


_Very important note: I've never used Athena in production, but on paper it makes enough sense for the requirements that
to not use it would make for a high-overhead, unnecessarily complicated infrastructure topology.  As it can handle querying against multiple data
formats against S3-hosted files while also federating against other DB systems, it makes a compelling case for handling
all parts of Requirement 4 just by existing (Everything except #4 is natively handled by the query language, #4 can be
handled using Sagemaker)._

The basis of all the analysis infrastructure for what we want to do is going to be AWS Athena.

Like most BI tools with a SQL interface, it supports parameterized queries to allow for external APIs to access known performant queries
while also saving cacheable results.  Views could be generated for common situations (e.g. all the devices
owned by a customer).  Generated results are stored in S3 for later use either by Athena, visualization tools like
QuickSight, or for external customer access (described in the next section).

Although another alternative would be using the data lake to populate a number of different types of databases (e.g. relational,
time-series) but the operational overhead in doing so would be multiplicative over Athena (coupled with the level of
database de-normalization, per-db schema design, and number of querying semantics to keep straight) feels like a one-way
ticket to loss of team productivity.

All that said, the biggest question is whether or not the S3 data needs to be processed after sanitization to better optimize
Athena's performance.  I genuinely have no answer to that, and I suspect it might not be totally knowable without real
data and meaningful queries to prioritize.

The other question is more fundamental to the product experience and analytical workflow itself: Before customers are
able to generate their own analyses, how exactly are they supposed to know what results exist?  If developers are
generating analyses on behalf of customers (e.g. as part of a service contract), what makes the association aside from
the workflow itself?  Does Athena allow for a fully-predetermined result location so a particular experiment + customer
result can always be accessible on the same path or do they need to be moved appropriately?   



### Product Infrastructure (Green)

The product infrastructure is your normal web application architecture, with a pre-compiled UI hosted on a CDN and
an API hosted via scalable web clusters.  Likely no more than two servers (with one for redundancy) since this is mostly
serving low QPS analytical results with basic navigation at present, but can scale as needed.

The application database itself serves as the master record and gatekeeper for knowing which devices and relevant
analytical results are owned/accessible by which customers.

Of particular note is that the database does not store the results, but instead leaves them in the Athena results S3
bucket.  This allows the application layer (after validating the customer can access the results in question) to
generate a pre-signed URL with a short-lived expiration time (e.g. 15 minutes) for the customer to download their
desired reports (The URL could be via the UI or sent to a customer email).

Note: There's a delineation between "HW Ops User" and "Customer" as, in my experience, there's usually a Customer Support
function which needs to have an enhanced view of what the customer sees in order to provide offline assistance as part
of a Help Desk function.  It doesn't matter here, but it's a shape on a diagram and I added it as a note to myself for
later discussion if need be.

### Future: Customer Analyses

As mentioned above, direct access to underlying resources is an operational risk (Option #1), but creating a query language can be
a product development struggle (Option #2).  To make things relatively straight forward, although it puts a fair amount of technical
burden on the customer, it could make sense to take one of two alternative approaches:

- Enable Cross-account Access to S3 files: Since the data is currently mapped on a per-device basis, it _might_ be possible to
  create an IAM profile where a known AWS account could directly access the S3 objects under a particular pathway.  The
  main advantage to this is the data seen by Hello World and the Customer is exactly the same object, but there may be
  issues in execution (IAM policy semantics might not handle sub-bucket permissions well, the IAM profile would need to be updated as
  device ownership is modified for the customer, etc.).  
- Duplicating the analytics chain: If the infrastructure is set up using current IaC practices (e.g. Terraform), the
  entire toolchain from S3 to Athena could be set up in a customer-specific AWS account with federated users and
  supporting workflows that will copy the data from the Hello World account into the Customer AWS account.  The effort
  on the customer could be alleviated if the associated queries/workbooks/analyses created by developers were shared
  out of band via code repository permissions, but it would still be a bit of a struggle if the customer didn't have a
  similar skillset in-house as well.  The solution ends up looking more like an on-prem situation at this point and 
  would bring the same associated headaches operationally, however.

Although there are limits to how many that can be created, access to Athena could be governed with an
"N workgroups per customer" for the short term for both of the above options (The primary two options would not need to
worry about this limitation, as there would only be one Athena workgroup in use per environment).  

Regardless of which approach is taken, the data used will always have to be at the pre-processed staging level.  This
means that is Kinesis is used, there will need to be an addition consumer / KQL pathway created to bring that data over
as well.

One special note here as well: These strategies are not mutually exclusive to each other.  It's entirely possible for
"alpha stage" customers to work with Options 3 or 4, which have much shorter runways to completion, while one of the
first two options is in development and validation for use with a broader customer base.


### Future: SIEM Integration

This was pretty hard to research as I've never actually _used_ an SIEM product before, so I have no real concept of
what they can actually support beyond what I was able to find through the administration documentation for a few
products (Primarily SumoLogic and Exabeam).

In general, I don't think the approach changes too much from the data access portion of the above section (enable access
to pre-sanitized data), although depending on what the SIEM system supports the infrastructure needs for development
could be far lighter:

- Cross account access could be enabled, with any Kinesis data being pushed via a secondary consumer specifically for 
  SIEM workloads. 
- If we are not worried about data duplication, the device data could simply be transferred across clouds
  or uploaded directly to the SIEM via a push mechanism as the processing pipeline at present (with verified device
  ownership).

The main issue I see is any latency guarantees that we may need to agree to as part of this.  I imagine it may change how
data is processed throughout the pipeline and cause some marked changed in infrastructure to support it.

## Communication

### Device to Ingress Services

The primary assumption here is that a device does not need to be associated with a customer to send information.

If that's true, than the only thing that is needed is a known set of endpoints to handle upload requests, JSON POSTs
(with any needed certificates on the device), and a known way for the device to be uniquely identified (e.g. Type and
hardware ID).

In short:

```
GET /device/upload/{deviceId}
  response: 200
    uploadUrl: url
POST /device/json/{deviceId}
  body:
    events: json[] 
  response: 204
```

### Ingress to S3

The architecture is largely push-driven based on cloud events, so there's not much to see (Permissions excepted, which
we are skipping).  DeviceIDs and environment data can be determined by the S3 location / Kinesis partition, so no
information needs to be explicitly passed outside the data for S3 workflows:

```
# File Upload
S3 Object Create Trigger -> Lambda -> S3 Sanitized Storage               # Smaller files
S3 Object Create Trigger -> (CloudWatch -> ECS) -> S3 Sanitized Storage  # Better for larger files, more moving config

# JSON Events
Web Request -> Kinesis PutRecords -> Lambda -> S3 Sanitized Storage 
```

### Production Web Server

Ignoring the finer details of authentication, the primary APIs are centered around results access.  Arguably, there
should be APIs for a number of other functions (registering/removing devices, user management, etc.), but the only one
needed is the results download request.  Although this could be sent via email, we will represent it as a response here:

```
GET /customer/{customerId}/analysis/{analysisId}
  response: 200
    downloadUrl: url
```

At a minimum, authentication would be handled by a basic password system.  More than likely, enterprise accounts would
need be secured via IdP federation (SAML, OAuth2.0, etc.).  In either case, the end result would generate a token to be
used for authentication to any other endpoint (e.g. `Authorization: Bearer <token>`), validated on each request by the
relevant plugins/homebrew auth on the backend of your web service architecture.
