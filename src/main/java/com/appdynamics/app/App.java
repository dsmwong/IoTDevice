package com.appdynamics.app;

import com.appdynamics.iot.AgentConfiguration;
import com.appdynamics.iot.DeviceInfo;
import com.appdynamics.iot.Instrumentation;
import com.appdynamics.iot.VersionInfo;
import com.appdynamics.iot.HttpRequestTracker;
import com.appdynamics.iot.events.CustomEvent;

import java.io.IOException;
import java.net.URL;
import java.util.Random;

import com.amazonaws.services.iot.client.*;

import com.pi4j.wiringpi.Gpio;
import com.pi4j.wiringpi.GpioUtil;
import com.pi4j.system.SystemInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

public class App 
{

    static final Logger logger = LoggerFactory.getLogger(App.class);

    public static final String APP_KEY = "AD-AAB-AAP-FWA";
    public static final String COLLECTOR_URL = "https://iot-col.eum-appdynamics.com";

    private static final String DemoTopic = "appd/demo/temperature";
    private static final AWSIotQos DemoTopicQos = AWSIotQos.QOS0;
    private static AWSIotMqttClient awsIotClient;

    public static class NonBlockingPublisher implements Runnable {
        private final AWSIotMqttClient awsIotClient;
        private String endpointIP;
        private String endpointPort;
        private double manualTemp;
        private boolean onPi = true;


        public NonBlockingPublisher(AWSIotMqttClient awsIotClient, String endpointIP, String endpointPort, boolean onPi)
        {
            this.awsIotClient = awsIotClient;
            this.endpointIP = endpointIP;
            this.endpointPort = endpointPort;
            this.onPi = onPi;
        }

        public JsonNode toJsonNode(JsonObject jsonObj) throws IOException {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readTree(jsonObj.toString());
        }

        @Override
        public void run() {
            double sensedTemp = 0;
            double sensedHumidity = 0;
            int eventCounter = 1;
            JsonObject node = new JsonObject();

            Random random = new Random();
            TempSensor aSensor = new TempSensor();

            while (true) {
                try {
                    System.out.print("Sensing...");
                    while (sensedTemp == 0 && onPi){
                        Thread.sleep(10);
                        node = aSensor.getSensorData();
                        sensedTemp = node.get("temp").getAsDouble();
                    }
                    if(!onPi){
                        node.addProperty("temp", random.nextInt(23 - 20 + 1) + 20);
                        node.addProperty("humidity", random.nextInt(75 - 70 + 1) + 70);
                    }

                    System.out.println(node.toString());
                    System.out.print("found temp: " + node.get("temp")+"\n");
                    System.out.print("found humidity: " + node.get("humidity")+"\n");

                    System.out.println("Pushing to API...");

                    pushToAPI(toJsonNode(node), endpointIP, endpointPort);

                    System.out.println("Pushing to AWS...");
                    AWSIotMessage message = new NonBlockingPublishListener(DemoTopic, DemoTopicQos, node.toString());
                    try {
                        awsIotClient.publish(message);
                    } catch (AWSIotException e) {
                        System.out.println(System.currentTimeMillis() + ": publish failed");
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        System.out.println(System.currentTimeMillis() + ": NonBlockingPublisher was interrupted");
                        return;
                    }
                        // BEGIN APPD SECTION

                        CustomEvent.Builder builder = CustomEvent
                                .builder("SR", "Sensor Reading")
                                .addDoubleProperty("Temperature", node.get("temp").getAsDouble())
                                .addDoubleProperty("Humidity", node.get("humidity").getAsDouble());
                        CustomEvent customEvent = builder.build();
                    Instrumentation.addEvent(customEvent);
                    System.out.println("Adding sensor data as event to AppD Payload");
                    if(eventCounter==5) {
                        sendEventNonBlocking();
                        System.out.println("Sending payload to AppD");

                        // END APPD SECTION
                        eventCounter = 1;
                    }
                    sensedTemp = 0;
                    Thread.sleep(500);
                }
                catch (Exception e)
                {
                    handleError(e);
                }
                eventCounter++;
            }
        }
    }

    public static void main( String[] args ) throws InterruptedException, AWSIotException
    {
        String endpointIP = args[0];
        String endpointPort = args[1];
        boolean onPi = true;

        System.out.println( "Hello World, i'm a thing!" );

        try{SystemInfo.getSerial();}
        catch(IOException ex)
        {
            onPi=false;
        }

        initInstrumentation(onPi);

        if(onPi) {
            System.out.print("Wiring GPIO...");
            // setup wiringPi
            if (Gpio.wiringPiSetup() == -1) {
                System.out.println(" ==>> GPIO SETUP FAILED");
                return;
            }
            System.out.print("wired!\n");
            GpioUtil.export(3, GpioUtil.DIRECTION_OUT);
        }
        else System.out.println("Running without Pi, generating temperature");

        CommandArguments arguments = CommandArguments.parse(args);
        initClient(arguments);
        awsIotClient.connect();

        AWSIotTopic topic = new DemoTopicListener(DemoTopic, DemoTopicQos);
        awsIotClient.subscribe(topic, true);

        Thread nonBlockingPublishThread = new Thread(new NonBlockingPublisher(awsIotClient, endpointIP, endpointPort, onPi));

        nonBlockingPublishThread.start();
        nonBlockingPublishThread.join();
    }

    private static void initInstrumentation(boolean onPi) {
        AgentConfiguration.Builder agentBuilder = AgentConfiguration.builder();
        AgentConfiguration agent = agentBuilder
                .withAppKey(APP_KEY)
                .withCollectorUrl(COLLECTOR_URL)
                .build();

        VersionInfo.Builder versionInfoBuilder = VersionInfo.builder();
        VersionInfo versionInfo = VersionInfo.builder().build();


        if(!onPi) {
            DeviceInfo.Builder deviceInfoBuilder = DeviceInfo.builder("Smart Thermometer", "56b534ab-3081-46aa-8eaf-2040ca6127db");
            DeviceInfo deviceInfo = deviceInfoBuilder.withDeviceName("Koala").build();
            versionInfo = versionInfoBuilder
                    .withFirmwareVersion("2.3.4")
                    .withHardwareVersion("1.6.7")
                    .withOsVersion("8.9.9")
                    .withSoftwareVersion("3.1.1").build();
            Instrumentation.start(agent, deviceInfo, versionInfo);

        }
        else
        {
            try {
                DeviceInfo.Builder deviceInfoBuilder = DeviceInfo.builder("Smart Thermometer", SystemInfo.getSerial());
                DeviceInfo deviceInfo = deviceInfoBuilder.withDeviceName("Koala").build();
                versionInfo = versionInfoBuilder
                        .withFirmwareVersion(SystemInfo.getOsFirmwareBuild())
                        .withHardwareVersion(SystemInfo.getRevision())
                        .withOsVersion(SystemInfo.getOsVersion())
                        .withSoftwareVersion(SystemInfo.getJavaVersion())
                        .build();
                Instrumentation.start(agent, deviceInfo, versionInfo);
            }
            catch (Exception ex) {System.out.println(ex.toString());}
        }
        return;
    }

    public static void sendEventNonBlocking() {
        Thread t = new Thread(new NonBlockingSender());
        t.start();
    }

    private static class NonBlockingSender implements Runnable {
        @Override
        public void run() {
            Instrumentation.sendAllEvents();
            System.out.println("AppD event send initiated");
        }
    }

    private static void handleError(Exception e)
    {
        System.out.println(e.toString());
    }

    private static void pushToAPI(JsonNode payload, String ip, String port)
    {
        int responseCode;
        String url = "http://"+ip +":"+port+"/sensor";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);

        // FOR APPD
        requestHeaders.set("ADRUM", "isAjax:true");
        requestHeaders.set("ADRUM_1", "isMobile:true");

        //logger.info("Request Headers: " + requestHeaders.toString());

        HttpEntity request = new HttpEntity(payload,requestHeaders);
        //logger.info("Request: " + request.toString());

        try{
            final HttpRequestTracker tracker = Instrumentation.beginHttpRequest(new URL(url));
            //System.out.println(tracker.toString());

            //ResponseEntity response = restTemplate.postForEntity( url, request , String.class );

            ResponseEntity response = restTemplate.exchange(url, HttpMethod.POST, request,
                    String.class);

            HttpHeaders headerFields = response.getHeaders();
            responseCode = response.getStatusCodeValue();

            //logger.info("Response Headers: " + headerFields.toString());

            // FOR APPD
            if (headerFields != null && headerFields.size() > 0){
                // [AppDynamics Instrumentation] Initiate adding NetworkRequestEvent
                if (responseCode >= 200 && responseCode < 300) {
                    tracker.withResponseCode(responseCode).withError(response.toString())
                            .withResponseHeaderFields(headerFields)
                            .reportDone();
                } else {
                    tracker.withResponseCode(responseCode).reportDone();
                }
            } else {
                tracker.withResponseCode(responseCode).reportDone();
            }
            Instrumentation.sendAllEvents();
        }
        catch (Exception e)
        {
            System.out.println(e.toString());
        }
    }

    public static void setClient(AWSIotMqttClient client) {
        awsIotClient = client;
    }

    private static void initClient(CommandArguments arguments) {
        String clientEndpoint = arguments.getNotNull("clientEndpoint", SampleUtil.getConfig("clientEndpoint"));
        String clientId = arguments.getNotNull("clientId", SampleUtil.getConfig("clientId"));

        String certificateFile = arguments.get("certificateFile", SampleUtil.getConfig("certificateFile"));
        String privateKeyFile = arguments.get("privateKeyFile", SampleUtil.getConfig("privateKeyFile"));
        if (awsIotClient == null && certificateFile != null && privateKeyFile != null) {
            String algorithm = arguments.get("keyAlgorithm", SampleUtil.getConfig("keyAlgorithm"));
            SampleUtil.KeyStorePasswordPair pair = SampleUtil.getKeyStorePasswordPair(certificateFile, privateKeyFile, algorithm);

            awsIotClient = new AWSIotMqttClient(clientEndpoint, clientId, pair.keyStore, pair.keyPassword);
        }

        if (awsIotClient == null) {
            String awsAccessKeyId = arguments.get("awsAccessKeyId", SampleUtil.getConfig("awsAccessKeyId"));
            String awsSecretAccessKey = arguments.get("awsSecretAccessKey", SampleUtil.getConfig("awsSecretAccessKey"));
            String sessionToken = arguments.get("sessionToken", SampleUtil.getConfig("sessionToken"));

            if (awsAccessKeyId != null && awsSecretAccessKey != null) {
                awsIotClient = new AWSIotMqttClient(clientEndpoint, clientId, awsAccessKeyId, awsSecretAccessKey,
                        sessionToken);
            }
        }

        if (awsIotClient == null) {
            throw new IllegalArgumentException("Failed to construct client due to missing certificate or credentials.");
        }
    }
}
