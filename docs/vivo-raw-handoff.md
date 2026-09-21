# Internal RAW handoff implementation

This is an SCAMERA transport boundary, not a recovered Vivo ABI or a completed
HAL connection. `vivo-raw-handoff.h` decodes an owned RAW16 copy and assembles
exactly four distinct normal frames, S, ES and L. An explicitly marked normal
reference is required. Arrival order is not used to identify the reference.

The transaction is armed with capture ID, generation and camera ID. Every frame
must carry the same transaction identity, an explicitly armed stream ID, a sensor timestamp and frame number unique
within that stream, and a timestamp-matched vendor AE snapshot. Any malformed, foreign,
duplicate or incomplete input closes the transaction. No partial burst or
synthetic replacement is returned. Exposure order is checked using measured
exposure time times total gain, not nominal EV or shutter alone.

The internal little-endian packet is version 2, magic VRF1, with a 292-byte
header. Offsets are documented by the decoder. RAW16 rows may include padding;
the receiver validates extents and copies pixels before releasing the source.
The AE block uses the existing 176-byte NiceAe transport. The intended Android
ARM64 producer and consumers are little-endian. RAW10/12 packing must be decoded
by a verified producer before this boundary; it must never be relabelled RAW16.

`vivo-raw-nice-input.h` adapts a completed transaction to the existing native
NICE Burst view. It owns the seven pixel arrays, carries per-frame AE and ISO,
normalizes measured exposure to the explicit reference and retains both normal
and long noise profiles. Copying/moving the owner is prohibited to protect raw
pointers. The view must not outlive its owner. Scene ADRC is left absent; CRE DRC
is not assumed to be a TCE field. The current graph requires common geometry,
CFA and black/white levels across the burst; varying calibration is rejected.

Host validation compiles the actual decoder and graph adapter with address and
undefined-behavior sanitizers. It covers padded pixels, reordered arrivals,
explicit reference, AE/RAW correspondence, exposure normalization, malformed
extents, mixed transaction identity, duplicate frames, missing roles and closed
transactions. LeakSanitizer is disabled because this execution environment uses
ptrace; the address and undefined-behavior checks remain enabled.

## Remaining production connection

No production caller currently feeds this boundary. The public VCF2 Buffer
callback has capture ID and buffer geometry but no per-RAW sensor timestamp.
The existing JNI reader accepts JPEG BLOB only. The stock NICE input trace proves
seven source slots and AE values, but does not establish a sensor identity map
across the capture and camera3rd processes. A producer must recover that map,
buffer ownership/locking and measured calibration before enabling delivery.
Neither callback arrival order nor NICE destination slot zero is a substitute.

The Camera2 capture scheduler still does not implement the stock AE plan. This
component does not change the sensor mode, RT sharpening, capture scheduling or
TCE. Full stock-equivalent capture and an APK are not yet ready.

## DMA identity observation (collector v13)

The supplied SQGhYcUe trace has the same virtual plane address for normal source
1 and sources 4/5/6, but different image+0x68 values. Virtual addresses cannot
identify those frames. The NICE adapter copies image+0x68 to CRE+0x28 at
0x10ae8..0x10aec. Queue holdMetadataLocked matches BufferInfo+0x24 then uses
BufferInfo+0x10 as the native-handle key at 0x12e08c..0x12e0d8.

Collector v13 reads bounded fdinfo during those observations, using existing
hook points and preserving the existing module hashes and SELinux rules.
Its analyzer compares DMA inode, allocation size and exporter across processes;
it explicitly reports reuse ambiguity and does not assert sensor identity.
Both supplied v12 traces lack fdinfo. Later device observations below supersede
that collection gap. No production RAW producer has been enabled based on
guessed associations.

## Returned buffers verified in v16

Local input: `scamera-zsl-trace.9wS3Z6Ih.tar.gz`. Both seven-input series have
complete NICE entry/return pairs: all 84 AE field copies and all 14 plane-pointer
copies match. Returned native-handle observations contain no delivery errors.
The slot order in these observations is N,N,N,N,L,S,ES; it is not a universal
arrival-order rule (earlier 4+3 traces had a different order).

Queue A is `0xb4000074b53e5db8`, B is `0xb4000074b5425778`, both in PID 1832.
These are observed returned-buffer candidates within queue preparation windows,
not established sensor frame identities:

| NICE source | Role by measured exposure | First series | Second series |
| --- | --- | --- | --- |
| 0 | N | A:466 | A:695 |
| 1 | N | A:467 | A:696 |
| 2 | N | A:468 | A:697 |
| 3 | N | A:469 | A:698 |
| 4 | L | A:485 | A:715 |
| 5 | S | B:469 | B:697 |
| 6 | ES | B:485 | B:715 |

Future ID equality across queues does not mean the same image. The observed
two queues each return four past buffers and one future buffer, while NICE
receives seven inputs. This does not change the seven-distinct-RAW graph target.
An old ready-record ID must never replace the ID of a newly returned buffer.

The analyzer retains full-history candidates, and adds observation-window
candidates requiring a prior return after the latest observed preparation of
the same queue. Cross-process equal-millisecond ordering is left unresolved.
Archive member order is not used for temporal ordering. Windows are diagnostic:
overlapping processing, missing observations and wall-clock changes remain
limitations; they must not authorize production frame delivery.

The second series also demonstrates per-frame DRC differences among normal
frames: approximately 2.45718, 2.48641, 2.51598, 2.51598. A single reference DRC
cannot be copied to every input. Both exposure and total gain remain required:
second-series N and S use the same 8.326225 ms shutter but different gains.

Still missing: the sensor timestamp/frame metadata association, the production
RAW producer and ownership/calibration contract, and stock AE scheduling wired
to CaptureController. The archive contains no RAW pixels and cannot verify
packing, image quality or stock-equivalent dynamic range. No APK completion is
claimed from these observations.


## v17 sensor metadata evidence

Input: scamera-zsl-trace.Ns4zIwxa.tar.gz. Fourteen returned buffers all have
handle-map Metadata pointers. The first series uses one queue with four past
and three future returns, not the paired alternate queues from v16.
The first-series observed timestamp values before its NICE processing finishes:

| Returned ID | NICE source/role | Observed sensor timestamp (ns) |
| --- | --- | --- |
| 105 | 0 / N | 17882964292142 |
| 106 | 1 / N | 17882997615475 |
| 107 | 2 / N | 17883030938809 |
| 108 | 3 / N | 17883064262142 |
| 126 | 4 / S | 17883303919060 |
| 127 | 5 / ES | 17883342074789 |
| 128 | 6 / L | 17883369372981 |

These are passive observations, not an atomic snapshot. Metadata objects for
107, 127 and 128 later acquire other timestamps. The second-series ID 259
reuses 128's Metadata pointer; assigning any first-series timestamp to 259
would be incorrect. V17 exhausted its 512 live-read quota before the second
series, so the absent observations cannot be recovered from that archive.

For all four normal buffers the standard exposure field reports 9999996 ns
and sensitivity 490, whereas NICE receives 3.601474 ms and gain 27.208839.
The reason is unresolved; do not normalize RAW using standard exposure in
place of the per-frame vendor AE. V18 adds passive observation of the known
35-float vendor AEC source and resets/deduplicates the per-prepare read quota.
Stock AE/RAW is still not connected to CaptureController; no new production
caller or image-quality equivalence is claimed by these observer changes.


## Stream identity correction from v18

Input: scamera-zsl-trace.6GL770BM.tar.gz. The user intentionally took two shots,
as in the previous run. Both shots have seven NICE inputs; all 84 AE copies and
14 plane-pointer copies match. Two shots are supported and are not a misuse of
the collector. The earlier v17 missing second-shot observations were a quota bug.

Queue A is 0xb4000074b543b918, queue B is 0xb4000074b53aa4a8 (PID 1832).

| NICE role | First shot returned buffer | Second shot returned buffer |
| --- | --- | --- |
| N0..N3 | A:86..89 | A:214..217 |
| L | A:105 | A:233 |
| S | B:88 | B:216 |
| ES | B:105 | B:233 |

Each matching A/B record ID references the same Metadata object while the image
buffers differ. In particular A:88 and B:88 share Metadata 0xb400007505171968,
whose only observed sensor timestamp in the entire archive is 18992441053684.
NICE still gets different normal and short gains. Neither a Metadata pointer
nor a timestamp alone identifies one exposure stream.

The internal transport now adds uint64 streamId at byte 284 and rejects v1.
Burst arming explicitly supplies normalStreamId and shortStreamId, which may
be equal for the single-queue route. N and L must use the former, S and ES the
latter. Duplicate timestamps/frame numbers remain forbidden within one stream;
they are allowed between the two explicitly armed streams. Four normals cannot
bypass duplicate rejection using invented alternate stream IDs. The producer
must derive stream identity from the configured source, not the input role or
an unverified queue address. Packet identity remains a producer contract.

Host tests exercise a reordered seven-frame dual-stream burst sharing N/S and
L/ES timestamps and frame numbers, preserve separate pixels/AE, reject wrong
streams and same-stream duplicates, and keep the single-stream route working.
The actual decoder and NICE adapter pass ASan/UBSan (LeakSanitizer disabled
because the execution environment uses ptrace).

V18 records no named vendor AEC reads. Inspection of the actual conversion
shows the source is read at VAS 0xdcd28 through
VASAdapterMetadataConvertVCF::getMetaData(void*,uint32_t,void*,size_t), using a
numeric tag resolved via VASAdapterMetadata::queryVendorTagLocation(std::string)
at 0xdccf8. Observing only MetadataImpl's two-C-string overload did not capture
this path. Repeating the same v18 run will not repair the missing observation.
The internal transport fix does not supply the production RAW producer, resolve
all sensor/AE associations or connect stock AE/RAW to CaptureController.

## V19 direct vendor AE observation

Input: scamera-zsl-trace.xulMpNj2.tar.gz. Seven NICE input entry/return pairs
were captured, with all 42 AE field copies and seven plane-pointer copies
matching. The VAS conversion hook recorded six successful 140-byte vendor AEC
copies with numeric tag 0x81220072. This is a tag observed on this build, not
a portable tag constant to put into the app. No VAS sensor scalar copies were
recorded. The metadata and NICE analyzers report no parsing errors or missing
NICE returns.

`analyze_sensor_metadata.py` now compares the recovered float32 exposure/gain
conversion with each NICE input, retaining all numerical candidates. It never
associates captures by order, nearest time, equal AE, or a pointer across PIDs.

| NICE source index | Exposure (ms) | Total gain | Numerically matching VAS reads | DRC comparison |
| --- | --- | --- | --- | --- |
| 0 | 9.995059 | 2.681518 | 1, 4 | both match |
| 1 | 9.995059 | 2.713411 | 2 | matches |
| 2 | 9.995059 | 2.745683 | 3, 5 | both match |
| 3 | 9.995059 | 2.745683 | 3, 5 | both match |
| 4 | 25.578068 | 2.745590 | 6 | vendor 1; NICE 2.5589845 |
| 5 | 2.236064 | 1.436348 | none | no matching exposure/gain copy |
| 6 | 0.133767 | 1.602785 | none | no matching exposure/gain copy |

In particular, retaining the vendor AEC DRC alone would not reproduce the
long-input NICE DRC. The existing `NiceAe::drcGain(true)` already requires the
separate HDR DRC field; this evidence does not authorize substituting the last
normal frame's DRC for that field. The six reads cannot be assigned to six
different graph slots: multiple reads have identical converted values.

None of the six VAS source pointers equals a returned queue Metadata pointer
in this archive. Several queue Metadata pointers also have multiple observed
sensor timestamps. No atomic RAW/AE/timestamp association is established.
Static inspection of VASAdapterMetadata::getMetadata at 0x12884c shows it
delegates via the object at this+0x50 (virtual slot 0x30), or falls back to a
camera metadata lookup when that adapter is absent. It does not establish
that VAS's opaque source pointer has the queue MetadataImpl layout.

The comparison tests cover float32 conversion, independent DRC, ambiguous
equal AE candidates and generator input. They do not validate a RAW producer
or a completed camera capture. CaptureController still has its manual bracket
and VCF2 JPEG route; no full-port APK was built from this observation.

## Scoped conversion binding (v20)

Further examination of v19 found standard timestamp reads for every VAS source
pointer, including pointers reused later. These are not the queue Metadata
pointers. The old collector lacks the enclosing conversion identity, so it
cannot safely choose between those timestamp observations.

The pinned VAS convertRawshotMetadataIn export at 0xdbaac receives the same
VCFProcessRequest pointer as convertShotMetadataToAlgoMetadata. The caller at
0x4b6f0..0x4b6fc passes it from vasAdapterProcess. Its input_frames vector is
at +0x78, with AlgoFrameInfo stride 0x98 (copy loop 0x4b50c..0x4b564).
Metadata is at +0xa8 (loads at 0xdcd10 and 0xe1930). Log arguments around
0x4b43c..0x4b49c name requestId at +0x10 and frameNumber at +0.
These IDs are not assumed to be the app's capture ID or sensor timestamp.

The timestamp read is at 0xe194c, through VASAdapterMetadata::getMetadata
(export 0x12884c), tag 0xe0010. The consumer loads the returned pointer and its
int64 value at 0xe1950..0xe195c. It bypasses the copy helper used for AEC;
this explains why v19 produced no vas_raw_metadata sensorScalar events.

V20 assigns a per-process conversion ID and per-thread call stack to this
export. The AE copy and timestamp read carry that ID only when their source
matches the enclosing request. The analyzer requires one valid AE and one
positive int64 timestamp, a matching return, unchanged source/header, and a
bounded input-frame vector. It rejects missing/duplicate/foreign reads instead
of choosing nearest time or equal AE. Opaque descriptors are retained with the
observed request/value tuple, without claiming DMA/stream/reference identity.

Host hook and analyzer tests pass for nested calls, reused Metadata pointers,
foreign threads/sources/call sites, failed reads, truncated transactions and
the 32-call bound. This is observation/binding code, not a production producer
or a CaptureController connection. A v20 phone trace is required to validate
the actual request/value tuples; sensor mode and camera memory remain unchanged.

## V20 phone validation: E91R4ZCO

Input scamera-zsl-trace.E91R4ZCO.tar.gz contains six complete scoped RAW
conversions and seven NICE inputs. Every conversion has one successful vendor
AE copy and one direct timestamp read from the same source in thread 11968,
PID 1832. All request headers and source pointers remain unchanged across each
observed call. The binding analyzer reports no errors or incomplete conversions.

All six request headers contain **vcfFrameNumber=76**, while their
vcfRequestId values differ. The analyzer now explicitly names the former
`vcfFrameNumber`; it must not populate an independently unique per-RAW number
in the transport. Neither value is assumed to be the Java/app capture ID.

| Conversion | VCF request ID | Sensor timestamp (ns) | Descriptor FDs (same PID) |
| --- | --- | --- | --- |
| 1 | 76 | 21293826843896 | 528, 975 |
| 2 | 78 | 21293877500563 | 510, 984 |
| 3 | 79 | 21293928157230 | 763, 971 |
| 4 | 80 | 21293984803392 | 798, 1014 |
| 5 | 81 | 21294012137230 | 502, 1022 |
| 6 | 82 | 21294062793896 | 636, 965 |

### Descriptor fields and observation candidates

AlgoFrameInfo FD at +0x44 and mapping length at +0x54 are established by the
fillSelectResult call sites 0x424e4..0x424f0 and 0x42f08..0x42f14. They pass
the descriptor FD/length into copyBuffer; its original code at 0x5ad38..0x5ad54
passes them to mmap. The descriptor stride is 0x98. Both inputs in every
conversion have mapping length 15728640. No CFA, pixel packing, stream ID,
ownership, or role is inferred from the remaining fields.

The analyzer retains every matching same-PID FD observation from successful
queue deliveries, then uses DMA inode/size/exporter to compare with NICE in
the other process. It does not equate FD integers across processes or choose
one observation when an FD has been reused. Each FD below has one candidate
in this archive; its lifetime across the two observation points is not proven.

| NICE index | Conversion/descriptor candidate | Queue record | DMA inode |
| --- | --- | --- | --- |
| 0 | 1/0 | A:85 | 123200 |
| 1 | 2/0 | A:86 | 123192 |
| 2 | 3/0 | A:87 | 123429 |
| 3 | 5/0 | A:89 | 123188 |
| 4 | 6/0 | A:90 | 123302 |
| 5 | 3/1 | B:87 | 123659 |
| 6 | 2/1 | B:86 | 123668 |

A=0xb4000074b5380838, B=0xb4000074b543e438. NICE reuses FD 50 for indices
0 and 6 with different DMA inodes. Conversion 4 has no selected NICE input
among its descriptor candidates. Arrival order therefore cannot supply the
graph order or the reference/short roles.

This scene's producer plan has zero past and six future requests, with echo
marker 101 at future index 3. NICE indices 0..4 all have EV 0, exposure
33.31538772583008 ms and total gain 14.12618637084961. Indices 5/6 both have
exposure 16.64872169494629 ms with different gains 2.162489414215088 and
1.4315401315689087. This is not evidence of a longer-exposed L input: graph
position 4 must not be relabelled as L without a verified producer role.
The requested 4N/S/ES/L app contract is not silently broadened by this trace.

Tests now cover the descriptor bounds, common VCF frame number naming,
same-PID FD matching, cross-PID DMA matching and ambiguous FD reuse. The
production RAW producer, stock scene/request scheduling and CaptureController
connection remain unfinished. No APK was built from these analysis changes.

## Executable RAW channel and worker input

`vivo-raw-channel.h` implements a real local stream transfer of VRF1/v2 RAW16
packets. `Channel` owns an already connected AF_UNIX/SOCK_STREAM descriptor,
checks the expected peer UID with SO_PEERCRED, and sets FD_CLOEXEC. Its caller
must establish the intended peer/process association when creating/passing
that socket. There is no filesystem listener, public port or authentication
based on an arbitrary packet field.

The sender validates each packet and copies its bytes through the socket with
partial-write handling and MSG_NOSIGNAL. The packet must be owned/immutable for
the duration of sendPacket. No provider pointers or numeric DMA FDs are sent
to another process. finishSending shuts down the write side after the series.
The receiver reads bounded headers/payloads, passes each packet to the armed
Burst transaction, and requires EOF immediately after the seventh packet.
Missing frames, malformed/foreign/duplicate inputs, trailing bytes, disconnects
and a monotonic deadline cancel the transaction and close the socket. Both
directions have one total transfer deadline, at most 60 seconds; partial I/O
does not restart it.

Native worker v30 now has a real consumer of this channel and NiceInput:

```text
vivo-neural-worker --nice-raw-stream MODEL_DIR SOCKET_FD CAPTURE_ID GENERATION CAMERA_ID NORMAL_STREAM_ID SHORT_STREAM_ID EXPECTED_PEER_UID OUTPUT_RGB
```

The connected socket FD must be inherited across exec. The worker requires
root, receives the seven packets, validates the complete role/reference graph,
and keeps NiceInput's owned pixels alive throughout the existing reconstruction
path. It uses the existing stock motion and NICE graph processing code. The
existing --nice-capture file input still uses that same processing path.

Verification: ASan/UBSan tests transfer real packets between forked processes
over a socketpair with a small send buffer. They verify reordered delivery,
pixels surviving sender-side mutation after send, metadata/reference/exposure
preservation, and cancellation on six frames, duplicates, foreign generation,
oversized headers, truncated headers, extra bytes and timeout. Wrong peer UIDs
and datagram sockets are rejected, and owned FDs close on rejection. Existing
RAW decoder/adapter tests pass after sharing their packet fixtures.

The complete worker compiles and links on the Linux host. A real exec with an
inherited socket receives all seven packets and logs all seven AE snapshots.
It then stops at stock motion initialization because this host lacks
/vendor/lib64/libvivo_nice_cre.so. That checks worker input routing, not Android
or NPU execution. No APK was built.

**Remaining connection:** the Vivo provider currently supplies packed image
descriptors, not these owned/calibrated RAW16 packets. Acquiring/copying pixels
within the provider's valid buffer lifetime, unpacking its verified format,
providing source stream/reference/role/calibration identity, and launching this
socket path from CaptureController are still missing. This channel does not
pretend that the VCF2 JPEG callback is a RAW producer or infer missing metadata
from trace order. No new collector or phone run is required merely to test this
host transport implementation.
