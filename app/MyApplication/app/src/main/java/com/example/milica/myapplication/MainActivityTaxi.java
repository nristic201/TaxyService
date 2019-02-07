package com.example.milica.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.LocationSource.OnLocationChangedListener;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nabinbhandari.android.permissions.PermissionHandler;
import com.nabinbhandari.android.permissions.Permissions;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeoutException;

public class MainActivityTaxi extends AppCompatActivity implements OnMapReadyCallback, TaxiReplyDialog.DialogListener {

    private static final String FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION;
    private static final String COURSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1234;

    ConnectionFactory factory = new ConnectionFactory();
    private BlockingDeque<String> queue = new LinkedBlockingDeque<>();
    private BlockingDeque<String> respondQueue = new LinkedBlockingDeque<>();
    private BlockingDeque<String> endRideQueue = new LinkedBlockingDeque<>();
    private BlockingDeque<String> startRideQueue = new LinkedBlockingDeque<>();

    private ArrayList<TaxiDriver> all_taxi_drivers;
    private Boolean mLocationPermissionsGranted = false;
    private MapResolver mapResolver;
    private CheckInternet checkInternet;
    private LocationManager locationManager;
    private Session session;
    private Thread publishThread;
    private Thread subscribeThread;
    private Thread receiveRequestsThread;
    private Thread responseToRequestThread;
    private Thread endRideThread;
    private Thread startRideThread;
    private Zahtev receivedRequest;
    private Location location;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationRequest locationRequest;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_taxi);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        getLocationPermission();


        mapResolver = new MapResolver(this);
        mapResolver.sendLocationRequest();
        session = new Session(this);
        all_taxi_drivers = new ArrayList<>();
        location = new Location("gps");

        setupConnectionFactory(Constants.hostName);
        SendMessage();
        SendStartRide();

        fusedLocationProviderClient = new FusedLocationProviderClient(this);
        locationRequest = new LocationRequest();
        locationRequest.setFastestInterval(2000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(4000);


        getPermissions();
        //PublishCallback();
        //final int i = 0;

        Button btnKrajVoznje = (Button) findViewById(R.id.btnKrajVoznje);
        Button btnPocetakVoznje = (Button) findViewById(R.id.btnPocetakVoznje);

        btnPocetakVoznje.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Location location = mapResolver.getLastKnownLocation();
                StartRide sr = new StartRide();
                sr.setUsername(session.getUser());
                sr.setLat_start(Double.toString(location.getLatitude()));
                sr.setLon_start(Double.toString(location.getLongitude()));

                Gson gson = new Gson();
                String msg = gson.toJson(sr);

                PushToStartRideQueue(msg);
            }
        });

        btnKrajVoznje.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Location location = mapResolver.getLastKnownLocation();
                EndRide er = new EndRide();
                er.setLat(Double.toString(location.getLatitude()));
                er.setLon(Double.toString(location.getLongitude()));
                er.setUsername(session.getUser());
                er.setQueueForResponse(receivedRequest.getQueueNameForResponse());
                Gson gson = new Gson();
                String msg = gson.toJson(er);
                PushToEndRideQueue(msg);
            }
        });

        @SuppressLint("HandlerLeak") final Handler incomingMessageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                String message = msg.getData().getString("msg");

                Toast.makeText(getApplicationContext(), Integer.toString(444444), Toast.LENGTH_SHORT).show();
                parseJSON(message);
                addTaxisToMap();
            }
        };

        @SuppressLint("HandlerLeak") final Handler requestMessageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {

                String message = msg.getData().getString("request");
                Gson gson = new Gson();
                receivedRequest = gson.fromJson(message, Zahtev.class);

                TaxiReplyDialog taxiReplyDialog = new TaxiReplyDialog();
                taxiReplyDialog.show(getSupportFragmentManager(),  "Taxi reply dialog");
            }
        };

        ReceiveRequests(requestMessageHandler);
        SubscribeToFanoutExchange(incomingMessageHandler);
        SendMessage();
        SendResponse();
        SendEndRide();
    }

    private void getLocationUpdates() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PermissionChecker.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PermissionChecker.PERMISSION_GRANTED) {

            fusedLocationProviderClient.requestLocationUpdates(locationRequest, new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    super.onLocationResult(locationResult);
                    Gson gson = new Gson();


                    Location loc = mapResolver.getLastKnownLocation();
                    if(locationResult.getLastLocation().getLongitude() != location.getLongitude() ||
                            locationResult.getLastLocation().getLatitude() != location.getLatitude()) {

                        double latitude = locationResult.getLastLocation().getLatitude();
                        double longitude = locationResult.getLastLocation().getLongitude();

                        session.setLatitude(Double.toString(latitude));
                        session.setLongitude(Double.toString(longitude));

                        TaxiDriver taxiDriver = new TaxiDriver(session.getUser(),
                                session.getPassword(),
                                session.getIme(),
                                Double.toString(latitude),
                                Double.toString(longitude),
                                session.getPrezime());
                        ArrayList<TaxiDriver> lista = new ArrayList<>();
                        lista.add(taxiDriver);

                        String userGson = gson.toJson(lista);
                        location = locationResult.getLastLocation();

                        PushToInternalQueue(userGson);
                    }


                }
            }, getMainLooper());
        }
        else {
            getPermissions();
        }

    }

    private void getPermissions() {
        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        String rationale = "Please provide location permission so that you can ...";
        Permissions.Options options = new Permissions.Options()
                .setRationaleDialogTitle("Info")
                .setSettingsDialogTitle("Warning");

        Permissions.check(this/*context*/, permissions, rationale, options, new PermissionHandler() {
            @Override
            public void onGranted() {
                // do your task.
                getLocationUpdates();
            }

            @Override
            public void onDenied(Context context, ArrayList<String> deniedPermissions) {
                // permission denied, block the feature.
                getPermissions();
            }
        });
    }

    private void PushToEndRideQueue(String msg) {
        try {
            endRideQueue.putLast(msg);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void PushToStartRideQueue(String msg) {
        try {
            startRideQueue.putLast(msg);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        publishThread.interrupt();
        subscribeThread.interrupt();
        receiveRequestsThread.interrupt();
        responseToRequestThread.interrupt();
    }

    private void addTaxisToMap() {

        for(TaxiDriver driver : all_taxi_drivers) {

            double lat = Double.parseDouble(driver.getKoordinateLat());
            double lon = Double.parseDouble(driver.getKoordinateLong());
            LatLng ll = new LatLng(lat, lon);
            mapResolver.getmMap().addMarker(new MarkerOptions().position(ll));
        }
    }

    private void parseJSON(String jsonString) {

        all_taxi_drivers = new ArrayList<>();
        Gson gson = new Gson();
        Type type = new TypeToken<List<TaxiDriver>>(){}.getType();
        List<TaxiDriver> driverList = gson.fromJson(jsonString, type);
        for (TaxiDriver driver : driverList){
            all_taxi_drivers.add(driver);
        }
    }

    public void SubscribeToFanoutExchange(final Handler handler) {

        subscribeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try
                {
                    Connection connection = factory.newConnection();
                    final Channel channel = connection.createChannel();

                    //while(true) {
                      //  channel.basicQos(1);
                        final AMQP.Queue.DeclareOk q = channel.queueDeclare(session.getUser() + new Date().getTime(),
                                true, false, false, null);

           //             while(true) {
                            channel.queueBind(q.getQueue(), "amq.fanout", "");

                            Consumer consumer = new DefaultConsumer(channel) {
                                @Override
                                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                                        throws IOException {

                                    String message = new String(body, "UTF-8");
                                    final long msgTag = envelope.getDeliveryTag();

                                    // channel.queueBind(q.getQueue(), "amq.fanout", "");

                                    channel.basicAck(msgTag, false);
                                    Message msg = handler.obtainMessage();
                                    Bundle bundle = new Bundle();
                                    bundle.putString("msg", message);
                                    msg.setData(bundle);
                                    handler.sendMessage(msg);
                                }
                            };
                            channel.basicConsume(q.getQueue(), false, consumer);
//                        }

                  //  }


                } catch (TimeoutException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        subscribeThread.start();
    }

   /* public void PublishCallback() {

        final Handler handler = new Handler();
        final int delay = 5000; //milliseconds

        handler.postDelayed(new Runnable(){
            public void run(){

                Gson gson = new Gson();

                location = mapResolver.getLastKnownLocation();
                Location loc = mapResolver.getLastKnownLocation();
                if(loc.getLongitude() != location.getLongitude() ||
                        loc.getLatitude() != location.getLatitude()) {

                    double latitude = location.getLatitude();
                    double longitude = location.getLongitude();

                    session.setLatitude(Double.toString(latitude));
                    session.setLongitude(Double.toString(longitude));

                    TaxiDriver taxiDriver = new TaxiDriver(session.getUser(),
                            session.getPassword(),
                            session.getIme(),
                            Double.toString(latitude),
                            Double.toString(longitude),
                            session.getPrezime());
                    ArrayList<TaxiDriver> lista = new ArrayList<>();
                    lista.add(taxiDriver);

                    String userGson = gson.toJson(lista);

                    PushToInternalQueue(userGson);

                    handler.postDelayed(this, delay);
                }


            }
        }, delay);
    }*/


    public void PushToRespondQueue(String str) {
        try {
            respondQueue.putLast(str);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void PushToInternalQueue(String str) {
        try {
            queue.putLast(str);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void ReceiveRequests(final Handler handler) {

        receiveRequestsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Connection connection = factory.newConnection();
                    Channel channel = connection.createChannel();

                    AMQP.Queue.DeclareOk q = channel.queueDeclare(session.getUser() +":zahtev", true,
                            false, false, null);

                    Consumer consumer = new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope,
                                                   AMQP.BasicProperties properties, byte[] body)
                                throws IOException {

                            String message = new String(body, "UTF-8");

                            Message msg = handler.obtainMessage();
                            Bundle bundle = new Bundle();
                            bundle.putString("request", message);
                            msg.setData(bundle);
                            handler.sendMessage(msg);
                        }
                    };
                    channel.basicConsume(q.getQueue(), true, consumer);

                } catch (TimeoutException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        receiveRequestsThread.start();
    }

    public void SendStartRide() {

        startRideThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true)
                {
                    try
                    {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();

                        while(true)
                        {
                            String message;
                                message = startRideQueue.takeFirst();
                                try {
                                    channel.basicPublish("",
                                            Constants.QUEUE_POCETAK_VOZNJE,
                                            null, message.getBytes("UTF-8"));
                                    channel.waitForConfirmsOrDie();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                        }

                    }
                    catch(Exception e)
                    {
                        Log.d("", "Connection broken: " + e.getClass().getName());

                    }
                }
            }
        });
        startRideThread.start();
    }

    public void SendEndRide() {
        endRideThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true)
                {
                    try
                    {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();

                        while(true)
                        {
                            String message;
                            if(endRideQueue.size() > 0) {
                                message = endRideQueue.takeFirst();
                                try {
                                    channel.basicPublish("",
                                            Constants.QUEUE_KRAJ_VOZNJE,
                                            null, message.getBytes("UTF-8"));
                                    channel.waitForConfirmsOrDie();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                    }
                    catch(Exception e)
                    {
                        Log.d("", "Connection broken: " + e.getClass().getName());

                    }
                }
            }
        });
        endRideThread.start();
    }

    void SendResponse() {

        responseToRequestThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true)
                {
                    try
                    {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();

                        while(true)
                        {
                            String message;
                            if(respondQueue.size() > 0) {
                                message = respondQueue.takeFirst();
                                try {
                                    channel.basicPublish("",
                                            "OdgovorQueue",
                                            null, message.getBytes("UTF-8"));
                                    channel.waitForConfirmsOrDie();
                                } catch (Exception e) {
                                    respondQueue.putFirst(message);
                                    e.printStackTrace();
                                }
                            }

                        }

                    }
                    catch(Exception e)
                    {
                        Log.d("", "Connection broken: " + e.getClass().getName());

                    }
                }
            }
        });
        responseToRequestThread.start();
    }

    void SendMessage() {

        publishThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true)
                {
                    try
                    {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();

                        while(true)
                        {
                            String message;
                                message = queue.takeFirst();
                                try {
                                    channel.basicPublish("",
                                            Constants.QUEUE_KOORDINATE_TAKSISTA,
                                            null, message.getBytes("UTF-8"));
                                    channel.waitForConfirmsOrDie();
                                } catch (Exception e) {
                                    queue.putFirst(message);
                                    e.printStackTrace();
                                }
                            }
                    }
                    catch(Exception e)
                    {
                        Log.d("", "Connection broken: " + e.getClass().getName());

                    }
                }
            }
        });
       publishThread.start();
    }

    public boolean CheckLocationParams() {

        if(!checkInternet.checkLocation(getApplicationContext()) || !checkInternet.checkLocation(getApplicationContext()) ||
                mapResolver.getLastKnownLocation() == null)
        {
            Toast.makeText(getApplicationContext(), "Location or internet not available", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {

        mapResolver.setmMap(googleMap);

        if (mLocationPermissionsGranted) {

            mapResolver.moveCamera(new LatLng(mapResolver.getLastKnownLocation().getLatitude(),
                    mapResolver.getLastKnownLocation().getLongitude()), 1f);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            mapResolver.getmMap().setMyLocationEnabled(true);
        }
    }

    private void initMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);

        mapFragment.getMapAsync(MainActivityTaxi.this);
    }

    private void getLocationPermission(){

        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};

        if(ContextCompat.checkSelfPermission(this.getApplicationContext(),
                FINE_LOCATION) == PackageManager.PERMISSION_GRANTED){
            if(ContextCompat.checkSelfPermission(this.getApplicationContext(),
                    COURSE_LOCATION) == PackageManager.PERMISSION_GRANTED){
                mLocationPermissionsGranted = true;
                initMap();
            }else{
                ActivityCompat.requestPermissions(this,
                        permissions,
                        LOCATION_PERMISSION_REQUEST_CODE);
            }
        }else{
            ActivityCompat.requestPermissions(this,
                    permissions,
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        mLocationPermissionsGranted = false;

        switch(requestCode){
            case LOCATION_PERMISSION_REQUEST_CODE:{
                if(grantResults.length > 0){
                    for(int i = 0; i < grantResults.length; i++){
                        if(grantResults[i] != PackageManager.PERMISSION_GRANTED){
                            mLocationPermissionsGranted = false;
                            return;
                        }
                    }
                    mLocationPermissionsGranted = true;
                    initMap();
                }
            }
        }
    }

    private void setupConnectionFactory(String uri) {
        try
        {
            factory.setAutomaticRecoveryEnabled(false);
            factory.setUri(uri);
        }
        catch(KeyManagementException | NoSuchAlgorithmException | URISyntaxException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void applyReply(String minutes) {

        Odgovor odgovor = new Odgovor();
        odgovor.setListeningQueue(receivedRequest.getQueueNameForResponse());

        if(minutes.compareTo("No") == 0) {
            odgovor.setConfirmed(false);
            odgovor.setPoruka("Can't take you rn");
            Gson gson = new Gson();
            String odg = gson.toJson(odgovor);
            PushToRespondQueue(odg);
        }
        else {

            odgovor.setConfirmed(true);
            odgovor.setPoruka(minutes);
            odgovor.setLat(receivedRequest.getMyLatitude());
            odgovor.setLon(receivedRequest.getMyLongitude());
            odgovor.setUsername(session.getUser());
            Gson gson = new Gson();
            String odg = gson.toJson(odgovor);
            PushToRespondQueue(odg);
        }
    }

}