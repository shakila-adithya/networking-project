# Java network programming: standalone agent instructions

## Scope and priority

This file is a self-contained implementation guide. It is intended to be
copied by itself into another project. No lecture PDFs, workshop
folders, ZIP archives, or original example files are required. Do not
ask for or attempt to read those materials to apply these instructions.

Apply these rules to this directory and all subdirectories. Explicit
user requirements take precedence. Otherwise, the implementation rules
below are mandatory for new code and changes to existing code. Keep the
result simple enough for a student to explain in a network programming
demonstration or viva.

The course teaches Java client/server sockets, TCP streams, UDP
datagrams, and threads. Its original examples show both ways of creating
threads. This project explicitly chooses **named classes implementing
`Runnable`**, executed using `new Thread(task).start()`. Do not extend
`Thread` in application workers. `Runnable` separates the task from the
thread and leaves the class free to extend another class; it is the
required design here, not a claim that thread subclassing is always
wrong. Resource, EOF, and shared-input rules below are robustness
requirements that supplement the short teaching examples.

## Required architecture and APIs

This is **not a JDK-only project**. Use standard JDK networking APIs for
the coursework concepts, and use additional libraries or platform APIs
whenever the actual product requirements need them. Do not reject a
suitable Bluetooth API or other dependency merely because it is outside
the JDK.

The following Java concepts are mandatory and must remain present in
appropriate parts of the application. Additional technologies may be
used alongside them, but must not replace them entirely:

-   `java.net.Socket` for appropriate TCP client-to-peer or service
    communication.
-   `java.net.ServerSocket` for an appropriate TCP listening service or
    peer endpoint.
-   `java.net.DatagramSocket` for appropriate UDP datagram
    communication, such as discovery or network announcements where
    supported by the chosen transport.
-   `Thread` and named classes implementing `Runnable` for explicit
    thread-based task execution and coursework demonstrations.
-   `java.util.Scanner` for the application's command-line or operator
    input path. Do not use `Scanner` to read network socket streams or
    to compete with another reader of `System.in`.

These APIs are required learning concepts, not a requirement to force
them into every feature. Use them where technically appropriate. For
example, native Bluetooth communication must use the relevant Bluetooth
APIs; do not pretend that a TCP `Socket` or UDP `DatagramSocket` is
itself a Bluetooth connection. Likewise, do not add a `ServerSocket` or
`DatagramSocket` to a component where the selected platform or transport
does not support or need it. Keep a clear, working implementation of
each mandatory concept in the overall application.

-   Use the transport required by each feature. TCP is
    connection-oriented and supplies an ordered byte stream. UDP is
    connectionless and sends separately addressed datagrams without
    guaranteed delivery, ordering, or duplicate suppression. Bluetooth
    Classic, Bluetooth Low Energy (BLE), Bluetooth Mesh, Wi-Fi Direct,
    and IP networking have different capabilities and constraints;
    select the transport based on the real device and operating-system
    support.
-   Real Bluetooth discovery and communication require platform APIs or
    a compatible Bluetooth library. Standard Java SE does not provide a
    general built-in Bluetooth API. Keep Bluetooth-specific code behind
    a clearly defined transport/service boundary so the rest of the
    application can be tested.
-   HTTP/REST, WebSockets, external libraries, frameworks, NIO,
    asynchronous APIs, and modern concurrency utilities are allowed when
    they solve a real requirement. Do not introduce them without a clear
    reason, and do not use them to erase the mandatory coursework
    concepts listed above.
-   Keep core networking behavior understandable and explainable in a
    student demonstration or viva. Prefer explicit message formats,
    documented protocol behavior, clear resource ownership, and focused
    abstractions over unnecessary frameworks.
-   A server provides a service and listens at an address and port. A
    client initiates communication with that service. Agree on the host,
    port, message format, encoding, sender order, exit behavior, and
    reply behavior at both ends.
-   Use the destination project's existing package layout and build
    system. Do not recreate the original lecture directory names or
    packages. New public class names must match their filenames.
-   The examples below are independent templates, not an instruction to
    create every class in every project. Adapt names, messages, and
    application logic while preserving the mandatory concepts and the
    correct transport behavior.

## Console input: Scanner and ScannerInput

Use `java.util.Scanner` for interactive terminal input:

``` java
import java.util.Scanner;

// Create once, in the application code that owns terminal input.
Scanner ScannerInput = new Scanner(System.in);
if (ScannerInput.hasNextLine()) {
    String output = ScannerInput.nextLine();
    System.out.println(output);
}
```

-   `Scanner` is the class and `ScannerInput` is the variable name used
    in the lecture. `scannerInput` is also acceptable; preserve local
    naming when editing. There is no built-in `ScannerInput()`,
    `scannerinput()`, or `acanneriput()` method. Do not invent these
    APIs.
-   Use `nextLine()` for complete messages, including spaces. Use
    `hasNextLine()` to handle console EOF cleanly. For menus, read a
    line and parse it with input validation; if using `nextInt()`,
    consume its remaining newline before the next `nextLine()`.
-   Give `System.in` one reader and one clear owner per application. Do
    not create a scanner in each client handler or let multiple threads
    compete for console input. Do not close the shared scanner from a
    worker: that closes `System.in`.
-   Network requests come from socket streams, not the console scanner.
    Automatic server replies must not wait for an operator to type a
    response. If server operator chat is required, use one console
    reader and explicitly route its messages to the selected client.

## Bluetooth and offline mesh networking

The product goal is a real offline, self-healing, device-to-device
communication network. Internet access must not be required for nearby
devices to discover one another, exchange messages, or transfer files.

-   Use actual Bluetooth hardware APIs or a suitable Bluetooth library
    for real device discovery and Bluetooth communication. Do not
    describe an IP-only Wi-Fi network or a simulator as real Bluetooth.
-   Choose the Bluetooth transport deliberately. BLE is useful for
    discovery and low-power communication; Bluetooth Classic may be
    useful for higher-throughput connections where supported. Bluetooth
    Mesh is a standardized option for managed multi-hop messaging. A
    custom BLE mesh requires the application to implement its own peer
    connections, forwarding, routing, retries, and recovery.
-   Do not assume that ordinary BLE connections automatically provide
    multi-hop mesh routing. Implement or adopt a mesh protocol that
    supports relaying messages through intermediate devices.
-   Support dynamic peer discovery and membership. Newly discovered
    devices may join after validation and authentication. Maintain peer
    identity and connection state, and handle devices that appear,
    disappear, or change addresses.
-   Implement message identifiers, duplicate suppression, hop
    limits/TTL, delivery acknowledgements or status where needed, and
    bounded retry behavior. UDP and Bluetooth broadcasts do not
    guarantee delivery.
-   Design routing and recovery so that when a peer disappears, the
    network can find another valid path when one exists. Do not promise
    connectivity when the remaining devices are physically partitioned
    or no alternative path exists.
-   Use store-and-forward only when required by the product:
    intermediate devices may temporarily retain messages for unavailable
    recipients, subject to storage limits, expiry, privacy, and delivery
    policy.
-   Protect the network with peer authentication and encryption
    appropriate to the chosen Bluetooth stack and application protocol.
    Do not treat discovery or possession of a Bluetooth address as
    sufficient authentication.
-   Account for mobile operating-system permissions, background
    execution limits, radio coexistence, battery use, connection limits,
    and vendor-specific behavior. Test on real target devices; a desktop
    simulation is useful for algorithm tests but does not prove hardware
    interoperability.
-   Keep the user interface transport-independent. The React or other UI
    should communicate with a local application service (for example,
    REST or WebSocket) where that architecture is selected. That local
    UI API is separate from the Bluetooth device-to-device transport and
    does not require Internet access.
-   Keep TCP sockets, UDP datagrams, Bluetooth APIs, and UI APIs as
    distinct transport choices with explicit adapters or service
    boundaries. Use each where it belongs; do not claim one transport is
    another.

## TCP rules

-   Server: `new ServerSocket(PORT)`, then a repeated
    `serverSocket.accept()`. `accept()` blocks and returns a separate
    connected `Socket` for one client. Keep the listening socket open
    while accepting subsequent clients.
-   Client: resolve the server using `InetAddress.getByName(host)`, then
    connect using `new Socket(ipAddress, PORT)`. The lecture also uses
    `InetAddress.getLocalHost()` for local examples; do not use that to
    locate a remote server. Use explicit loopback for local tests and a
    configurable host for different machines.
-   Receive text with
    `new BufferedReader(new InputStreamReader(   socket.getInputStream()))`
    and `readLine()`. Send text with
    `new PrintWriter(socket.getOutputStream(), true)` and `println()`.
    The `true` enables automatic flushing on `println()`.
-   Define who sends first. The teaching chat pattern sends a server
    greeting, then the client sends a line and the server replies. Two
    peers both waiting to read first can deadlock. Update both ends
    together when changing a protocol.
-   End a receive loop when `readLine()` returns `null`; it means peer
    EOF. Never repeatedly print or process `null` as a message.
-   TCP does not preserve message boundaries. Newlines frame this text
    protocol. Do not assume one read equals one message. If binary/file
    transfer is needed, define explicit lengths/framing and use
    appropriate streams on the same socket; do not send arbitrary binary
    data through `readLine()` or `println()`.
-   For multi-client services, accept and launch a separate `Runnable`
    promptly. Keep each client's reading, processing, and replies inside
    its handler. One idle client must not block other clients or the
    accept loop.

### Complete TCP reference: server and Runnable handler

Save as `TcpServer.java` when using this template. It uses the threaded
chat structure with automatic replies to avoid competing readers of
server input. `/quit` and `BYE` are example protocol choices, not
required lecture keywords.

``` java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpServer {
    private static final int PORT = 6677;

    public static void main(String[] args) throws IOException {
        int clientId = 1;
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("TCP server listening on " + PORT);
            while (true) {
                Socket client = serverSocket.accept();
                try {
                    ClientHandler task = new ClientHandler(client, clientId++);
                    Thread worker = new Thread(task);
                    worker.start();
                } catch (RuntimeException | Error failure) {
                    // Ownership did not successfully transfer to a worker.
                    try {
                        client.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
            }
        }
    }
}

class ClientHandler implements Runnable {
    private final Socket client;
    private final int clientId;

    ClientHandler(Socket client, int clientId) {
        this.client = client;
        this.clientId = clientId;
    }

    @Override
    public void run() {
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println("Welcome to Chat Application");
            if (out.checkError()) {
                return;
            }
            String input;
            while ((input = in.readLine()) != null) {
                System.out.println("Client " + clientId + " says: " + input);
                if ("/quit".equals(input)) {
                    out.println("BYE");
                    break;
                }
                out.println("Received: " + input);
                if (out.checkError()) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Client " + clientId + ": " + e.getMessage());
        }
    }
}
```

### Complete TCP reference: console client

Save as `TcpClient.java`. Run the server first. The optional first
argument is its hostname or IP address. This is turn-based request/reply
chat. For unsolicited messages or full-duplex chat, add a named
receiving `Runnable` while keeping one console-input owner and defining
who owns socket cleanup.

``` java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Scanner;

public class TcpClient {
    private static final int PORT = 6677;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        Scanner ScannerInput = new Scanner(System.in);
        try (Socket socket = new Socket(InetAddress.getByName(host), PORT);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            String input = in.readLine();
            if (input == null) {
                return;
            }
            System.out.println("Server says: " + input);
            while (ScannerInput.hasNextLine()) {
                String output = ScannerInput.nextLine();
                out.println(output);
                if (out.checkError()) {
                    System.err.println("Could not send message");
                    break;
                }
                input = in.readLine();
                if (input == null) {
                    break;
                }
                System.out.println("Server says: " + input);
                if ("/quit".equals(output)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
        // System.in remains owned by the application, never a client worker.
    }
}
```

## Threading rules and standalone Runnable example

The project must retain and demonstrate both `Runnable` and `Thread`,
but it is not restricted to manually creating platform threads for every
task.

-   Implement appropriate background tasks as named classes with
    `implements   Runnable` and `@Override public void run()`. Pass each
    task's state through its constructor and keep it in instance fields.
    Client sockets/streams must not be static.
-   Keep at least one clear, meaningful use of
    `Thread worker = new Thread(task);   worker.start();` in the
    application or its focused networking demonstration. Do not replace
    all explicit `Thread`/`Runnable` usage with another API. Calling
    `task.run()` directly executes on the caller's thread. A `Thread`
    instance can be started only once.
-   Modern concurrency APIs are allowed alongside the required `Thread`
    and `Runnable` concepts. Use `ExecutorService`,
    `ScheduledExecutorService`, `ThreadPoolExecutor`, virtual threads,
    `CompletableFuture`, or structured concurrency when justified by the
    workload and supported by the target JDK. These tools must
    complement, not eliminate, the required explicit `Thread`/`Runnable`
    implementation.
-   Prefer managed executors for large or recurring workloads where
    lifecycle, bounded concurrency, cancellation, and backpressure
    matter. Use scheduled executors for periodic jobs such as heartbeats
    when appropriate. Use virtual threads for large numbers of blocking
    I/O tasks when supported. Do not create unbounded numbers of
    platform threads.
-   Do not use application classes extending `Thread` for task
    implementations. Keep the task separate from its execution mechanism
    by using `Runnable`.
-   Do not `join()` immediately after starting each client handler; that
    serializes client service. To wait for a finite set of tasks, start
    them all, then join.
-   `Thread.sleep(...)` is an intentional delay, not synchronization or
    a way to prove a server is ready. On `InterruptedException`, restore
    the interrupt flag with `Thread.currentThread().interrupt()` and
    leave the worker loop.
-   A process is a running program; its threads share process resources.
    Concurrency permits overlapping progress and does not promise
    simultaneous execution or deterministic print order. Protect
    genuinely shared mutable data with suitable synchronization or
    thread-safe data structures. Do not use sleeps to prevent races.
-   Conceptually, threads progress through new, runnable/running,
    blocked/waiting, and terminated states. Java's actual `Thread.State`
    values are `NEW`, `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`,
    and `TERMINATED`; Java does not expose a separate `RUNNING` enum
    value.

Save as `RunnableDemo.java` for the finite three-worker teaching
pattern:

``` java
public class RunnableDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread[] workers = new Thread[3];
        for (int i = 0; i < workers.length; i++) {
            CountingTask task = new CountingTask(i + 1);
            workers[i] = new Thread(task);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }
}

class CountingTask implements Runnable {
    private final int threadNumber;

    CountingTask(int threadNumber) {
        this.threadNumber = threadNumber;
    }

    @Override
    public void run() {
        for (int i = 1; i <= 5; i++) {
            System.out.println(threadNumber + " : " + i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
```

## UDP rules and complete examples

-   Use `DatagramSocket` and `DatagramPacket` directly. There is no
    TCP-style `accept()` or per-client stream connection in this
    pattern.
-   Sender: `new DatagramSocket()` chooses an ephemeral local port.
    Create a
    `DatagramPacket(byte[] buf, int length, InetAddress address, int port)`
    and call `send(packet)`.
-   Receiver: bind with `new DatagramSocket(PORT)`. Allocate a buffer
    and a `DatagramPacket(byte[] buf, int length)`, then call
    `receive(packet)`.
-   Decode with the received packet's `getData()`, `getOffset()`, and
    `getLength()`. Never decode the entire unused buffer. Lengths are
    bytes, not `String.length()` character counts. Use matching encoding
    at both ends.
-   For a reply, address the outgoing packet with the request's
    `getAddress()` and `getPort()`; do not assume the client's source
    port equals the server port.
-   If reusing a receive packet, reset its length to buffer capacity
    before the next receive, or allocate a fresh packet each iteration.
-   The lecture buffer size of 256 bytes is an example, not a universal
    maximum. Document an appropriate message bound; oversized datagrams
    may be truncated.
-   Delivery, order, and duplicate suppression are not guaranteed. Add
    bounded retries, acknowledgements, sequence numbers, or timeouts
    only as required.
-   The templates below intentionally receive/send one message and exit.
    Add a receive loop only when a persistent service is required.
    Always close sockets.

Save as `UdpServer.java`:

``` java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

public class UdpServer {
    private static final int PORT = 4420;

    public static void main(String[] args) throws IOException {
        try (DatagramSocket server = new DatagramSocket(PORT)) {
            byte[] buf = new byte[256];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            server.receive(packet);
            String response = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
            System.out.println("Response Data: " + response);
        }
    }
}
```

Save as `UdpClient.java`:

``` java
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class UdpClient {
    private static final int PORT = 4420;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        InetAddress ipAddress = InetAddress.getByName(host);
        try (DatagramSocket client = new DatagramSocket()) {
            String message = "Hello World";
            byte[] buf = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pac = new DatagramPacket(
                    buf, buf.length, ipAddress, PORT);
            client.send(pac);
        }
    }
}
```

The TCP templates retain the lecture's default-charset reader/writer
constructors. When supporting non-ASCII TCP text across different
systems, specify the same encoding at both ends, for example
`InputStreamReader(input, StandardCharsets.UTF_8)` and
`PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)`.
Keep `BufferedReader.readLine()` and `PrintWriter.println()` as the text
APIs.

## Resource ownership, errors, and shutdown

-   Use the lecture's `try`/`finally` cleanup or try-with-resources.
    Release sockets and streams on normal completion, EOF, exceptions,
    and partial initialization. A failed close must not prevent
    remaining owned resources from being released.
-   The server owns its listening socket; a successfully launched
    handler owns its accepted socket and streams. Close undispatched
    sockets if handler creation or launch fails. Each UDP endpoint owns
    its datagram socket.
-   Handle `IOException` inside `run()` because `Runnable.run()` cannot
    declare it. A client's ordinary I/O failure must not stop the whole
    multi-client server.
-   Report useful errors. Do not swallow exceptions in empty catch
    blocks. `PrintWriter` suppresses write exceptions; use
    `checkError()` when detecting send failure is needed. Validate input
    without killing unrelated sessions.
-   Do not use `System.exit()` inside handlers or as a substitute for
    cleanup.
-   If graceful shutdown is required, close listening/connected/datagram
    sockets to unblock the relevant blocking calls and distinguish
    intentional closure from unexpected errors. Interrupting alone does
    not reliably terminate classic blocking socket I/O. Coordinate
    console-reader shutdown separately.
-   An infinite accept loop is suitable for the basic service example;
    do not claim the reference server already implements a coordinated
    shutdown command.

## Portable build and verification

Use the destination project's existing build tools first. Do not replace
its build system or invent references to files that were not copied. The
embedded examples use the default package only to make them
independently compilable. If materialized as the five named Java files
in one directory, compile/run with:

``` sh
javac -d build TcpServer.java TcpClient.java RunnableDemo.java UdpServer.java UdpClient.java
java -cp build TcpServer
# In a second terminal:
java -cp build TcpClient 127.0.0.1
# Separately, start the one-message UDP server, then its client:
java -cp build UdpServer
# In a second terminal:
java -cp build UdpClient 127.0.0.1
# The finite thread demonstration:
java -cp build RunnableDemo
```

There are five public source files in these commands; `ClientHandler`
and `CountingTask` are package-private classes embedded in their
corresponding files. With packages, compile using `javac -d` and run
fully qualified class names. Quote paths containing spaces. Keep
generated `.class` and build output out of source changes.

-   Ports in the templates are TCP 6677 and UDP 4420. Historical course
    examples also used TCP 6600 for a greeting server and TCP 7777 for
    console chat. These are examples, not required ports in the
    destination project. Choose/document matching ports and hosts, avoid
    conflicts, and start the server before clients.
-   After code changes, compile affected packages and perform focused
    checks. For TCP, verify greeting, a message containing spaces,
    reply, `/quit` if used, EOF, and a second client while the first
    remains idle. One client disconnecting must not stop the server.
    Bound automated runs and stop only processes started for those
    checks.
-   For UDP templates, expect `Response Data: Hello World` and server
    exit; restart the server before another send. Do not claim a
    successful `send()` proves that the receiver received the datagram.
-   For the Runnable demo, expect each worker's numbers 1 through 5;
    interleaving may vary. Check both compilation and adherence to the
    required `Runnable` launch pattern; compilation alone does not
    establish compliance.
-   Do not introduce a test framework for a small exercise. Use focused
    smoke checks. Documentation-only edits need no application test
    suite; if embedded runnable examples change, compiling and
    smoke-checking those examples is useful.
-   Explain what changed, which required pattern it follows, and what
    was actually verified. State limitations honestly. Never claim
    unavailable lecture files were inspected in the destination project
    or claim unperformed tests passed.
