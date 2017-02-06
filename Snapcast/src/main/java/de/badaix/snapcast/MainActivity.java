/*
 *     This file is part of snapcast
 *     Copyright (C) 2014-2017  Johannes Pohl
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.badaix.snapcast;

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.UnknownHostException;
import java.util.ArrayList;

import de.badaix.snapcast.control.RemoteControl;
import de.badaix.snapcast.control.json.Client;
import de.badaix.snapcast.control.json.Group;
import de.badaix.snapcast.control.json.ServerStatus;
import de.badaix.snapcast.control.json.Stream;
import de.badaix.snapcast.utils.NsdHelper;
import de.badaix.snapcast.utils.Settings;
import de.badaix.snapcast.utils.Setup;

public class MainActivity extends AppCompatActivity implements GroupItem.GroupItemListener, RemoteControl.RemoteControlListener, SnapclientService.SnapclientListener, NsdHelper.NsdHelperListener {

    static final int CLIENT_PROPERTIES_REQUEST = 1;
    static final int GROUP_PROPERTIES_REQUEST = 2;
    private static final String TAG = "Main";
    private static final String SERVICE_NAME = "Snapcast";// #2";
    boolean bound = false;
    private MenuItem miStartStop = null;
    private MenuItem miSettings = null;
    //    private MenuItem miRefresh = null;
    private String host = "";
    private int port = 1704;
    private int controlPort = 1705;
    private RemoteControl remoteControl = null;
    private ServerStatus serverStatus = null;
    private SnapclientService snapclientService;
    private GroupListFragment groupListFragment;
    private TabLayout tabLayout;
    private Snackbar warningSamplerateSnackbar = null;
    private int nativeSampleRate = 0;
    private CoordinatorLayout coordinatorLayout;
    private Button btnConnect = null;


    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            SnapclientService.LocalBinder binder = (SnapclientService.LocalBinder) service;
            snapclientService = binder.getService();
            snapclientService.setListener(MainActivity.this);
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        for (int rate : new int[]{8000, 11025, 16000, 22050, 44100, 48000}) {  // add the rates you wish to check against
            Log.d(TAG, "Samplerate: " + rate);
            int bufferSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize > 0) {
                Log.d(TAG, "Samplerate: " + rate + ", buffer: " + bufferSize);
            }
        }

        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            String rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            nativeSampleRate = Integer.valueOf(rate);
//            String size = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
//            tvInfo.setText("Sample rate: " + rate + ", buffer size: " + size);
        }

        coordinatorLayout = (CoordinatorLayout) findViewById(R.id.myCoordinatorLayout);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        btnConnect = (Button) findViewById(R.id.btnConnect);
        btnConnect.setVisibility(View.GONE);
        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.

        groupListFragment = (GroupListFragment) getSupportFragmentManager().findFragmentById(R.id.groupListFragment);
        groupListFragment.setHideOffline(Settings.getInstance(this).getBoolean("hide_offline", false));

        setActionbarSubtitle("Host: no Snapserver found");

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "copying snapclient");
                Setup.copyBinAsset(MainActivity.this, "snapclient", "snapclient");
                Log.d(TAG, "done copying snapclient");
            }
        }).start();

    }

    public void checkFirstRun() {
        PackageInfo pInfo = null;
        try {
            pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            final int verCode = pInfo.versionCode;
            int lastRunVersion = Settings.getInstance(this).getInt("lastRunVersion", 0);
            Log.d(TAG, "lastRunVersion: " + lastRunVersion + ", version: " + verCode);
            if (lastRunVersion < verCode) {
                // Place your dialog code here to display the dialog
                new AlertDialog.Builder(this).setTitle(R.string.first_run_title).setMessage(R.string.first_run_text).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Settings.getInstance(MainActivity.this).put("lastRunVersion", verCode);
                    }
                }).setCancelable(true).show();
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_snapcast, menu);
        miStartStop = menu.findItem(R.id.action_play_stop);
        miSettings = menu.findItem(R.id.action_settings);
//        miRefresh = menu.findItem(R.id.action_refresh);
        updateStartStopMenuItem();
        boolean isChecked = Settings.getInstance(this).getBoolean("hide_offline", false);
        MenuItem menuItem = menu.findItem(R.id.action_hide_offline);
        menuItem.setChecked(isChecked);
//        setHost(host, port, controlPort);
        if (remoteControl != null) {
            updateMenuItems(remoteControl.isConnected());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            ServerDialogFragment serverDialogFragment = new ServerDialogFragment();
            serverDialogFragment.setHost(Settings.getInstance(this).getHost(), Settings.getInstance(this).getStreamPort(), Settings.getInstance(this).getControlPort());
            serverDialogFragment.setAutoStart(Settings.getInstance(this).isAutostart());
            serverDialogFragment.setListener(new ServerDialogFragment.ServerDialogListener() {
                @Override
                public void onHostChanged(String host, int streamPort, int controlPort) {
                    setHost(host, streamPort, controlPort);
                    startRemoteControl();
                }

                @Override
                public void onAutoStartChanged(boolean autoStart) {
                    Settings.getInstance(MainActivity.this).setAutostart(autoStart);
                }
            });
            serverDialogFragment.show(getSupportFragmentManager(), "serverDialogFragment");
//            NsdHelper.getInstance(this).startListening("_snapcast._tcp.", SERVICE_NAME, this);
            return true;
        } else if (id == R.id.action_play_stop) {
            if (bound && snapclientService.isRunning()) {
                stopSnapclient();
            } else {
                item.setEnabled(false);
                startSnapclient();
            }
            return true;
        } else if (id == R.id.action_hide_offline) {
            item.setChecked(!item.isChecked());
            Settings.getInstance(this).put("hide_offline", item.isChecked());
            groupListFragment.setHideOffline(item.isChecked());
            return true;
        } else if (id == R.id.action_refresh) {
            startRemoteControl();
            remoteControl.getServerStatus();
        } else if (id == R.id.action_about) {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateStartStopMenuItem() {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (bound && snapclientService.isRunning()) {
                    Log.d(TAG, "updateStartStopMenuItem: ic_media_stop");
                    miStartStop.setIcon(R.drawable.ic_media_stop);
                } else {
                    Log.d(TAG, "updateStartStopMenuItem: ic_media_play");
                    miStartStop.setIcon(R.drawable.ic_media_play);
                }
                if (miStartStop != null) {
                    miStartStop.setEnabled(true);
                }
            }
        });
    }

    private void startSnapclient() {
        if (TextUtils.isEmpty(host))
            return;

        Intent i = new Intent(this, SnapclientService.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(SnapclientService.EXTRA_HOST, host);
        i.putExtra(SnapclientService.EXTRA_PORT, port);
        i.setAction(SnapclientService.ACTION_START);

        startService(i);
    }

    private void stopSnapclient() {
        if (bound)
            snapclientService.stopPlayer();
//        stopService(new Intent(this, SnapclientService.class));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }


    private void startRemoteControl() {
        if (remoteControl == null)
            remoteControl = new RemoteControl(this);
        if (!host.isEmpty())
            remoteControl.connect(host, controlPort);
    }

    private void stopRemoteControl() {
        if ((remoteControl != null) && (remoteControl.isConnected()))
            remoteControl.disconnect();
        remoteControl = null;
    }


    @Override
    public void onResume() {
        super.onResume();
        startRemoteControl();
        checkFirstRun();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (TextUtils.isEmpty(Settings.getInstance(this).getHost()))
            NsdHelper.getInstance(this).startListening("_snapcast._tcp.", SERVICE_NAME, this);
        else
            setHost(Settings.getInstance(this).getHost(), Settings.getInstance(this).getStreamPort(), Settings.getInstance(this).getControlPort());

        Intent intent = new Intent(this, SnapclientService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        stopRemoteControl();
        super.onDestroy();
    }

    @Override
    public void onStop() {
        super.onStop();

        NsdHelper.getInstance(this).stopListening();
// Unbind from the service
        if (bound) {
            unbindService(mConnection);
            bound = false;
        }
    }

    @Override
    public void onPlayerStart(SnapclientService snapclientService) {
        Log.d(TAG, "onPlayerStart");
        updateStartStopMenuItem();
    }

    @Override
    public void onPlayerStop(SnapclientService snapclientService) {
        Log.d(TAG, "onPlayerStop");
        updateStartStopMenuItem();
        if (warningSamplerateSnackbar != null)
            warningSamplerateSnackbar.dismiss();
    }

    @Override
    public void onLog(SnapclientService snapclientService, String timestamp, String logClass, String msg) {
        Log.d(TAG, "[" + logClass + "] " + msg);
        if ("state".equals(logClass)) {
            if (msg.startsWith("sampleformat")) {
                msg = msg.substring(msg.indexOf(":") + 2);
                Log.d(TAG, "sampleformat: " + msg);
                if (msg.indexOf(':') > 0) {
                    int samplerate = Integer.valueOf(msg.substring(0, msg.indexOf(':')));

                    if (warningSamplerateSnackbar != null)
                        warningSamplerateSnackbar.dismiss();

                    if ((nativeSampleRate != 0) && (nativeSampleRate != samplerate)) {
                        warningSamplerateSnackbar = Snackbar.make(coordinatorLayout,
                                getString(R.string.wrong_sample_rate, samplerate, nativeSampleRate), Snackbar.LENGTH_INDEFINITE);
                        warningSamplerateSnackbar.show();
                    } else if (nativeSampleRate == 0) {
                        warningSamplerateSnackbar = Snackbar.make(coordinatorLayout,
                                getString(R.string.unknown_sample_rate), Snackbar.LENGTH_LONG);
                        warningSamplerateSnackbar.show();
                    }
                }
            }
        } else if ("err".equals(logClass) || "Emerg".equals(logClass) || "Alert".equals(logClass) || "Crit".equals(logClass) || "Err".equals(logClass)) {
            if (warningSamplerateSnackbar != null)
                warningSamplerateSnackbar.dismiss();
            warningSamplerateSnackbar = Snackbar.make(findViewById(R.id.myCoordinatorLayout),
                    msg, Snackbar.LENGTH_LONG);
            warningSamplerateSnackbar.show();
        }
    }

    @Override
    public void onError(SnapclientService snapclientService, String msg, Exception exception) {
        updateStartStopMenuItem();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_CANCELED) {
            return;
        }
        if (requestCode == CLIENT_PROPERTIES_REQUEST) {
            Client client = null;
            try {
                client = new Client(new JSONObject(data.getStringExtra("client")));
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }

            Client clientOriginal = null;
            try {
                clientOriginal = new Client(new JSONObject(data.getStringExtra("clientOriginal")));
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }
            Log.d(TAG, "new name: " + client.getConfig().getName() + ", old name: " + clientOriginal.getConfig().getName());
            if (!client.getConfig().getName().equals(clientOriginal.getConfig().getName()))
                remoteControl.setName(client, client.getConfig().getName());
            Log.d(TAG, "new latency: " + client.getConfig().getLatency() + ", old latency: " + clientOriginal.getConfig().getLatency());
            if (client.getConfig().getLatency() != clientOriginal.getConfig().getLatency())
                remoteControl.setLatency(client, client.getConfig().getLatency());
            serverStatus.updateClient(client);
            groupListFragment.updateServer(MainActivity.this.serverStatus);
        } else if (requestCode == GROUP_PROPERTIES_REQUEST) {
            String groupId = data.getStringExtra("group");
            boolean changed = false;
            if (data.hasExtra("clients")) {
                ArrayList<String> clients = data.getStringArrayListExtra("clients");
                remoteControl.setClients(groupId, clients);
                changed = true;
            }
            if (data.hasExtra("stream")) {
                String streamId = data.getStringExtra("stream");
                remoteControl.setStream(groupId, streamId);
                changed = true;
            }
//TODO
//            if (changed)
//                remoteControl.getServerStatus();
        }
    }

    @Override
    public void onConnected(RemoteControl remoteControl) {
        setActionbarSubtitle(remoteControl.getHost());
        remoteControl.getServerStatus();
        updateMenuItems(true);
    }

    @Override
    public void onConnecting(RemoteControl remoteControl) {
        setActionbarSubtitle("connecting: " + remoteControl.getHost());
    }

    @Override
    public void onDisconnected(RemoteControl remoteControl, Exception e) {
        Log.d(TAG, "onDisconnected");
        serverStatus = new ServerStatus();
        groupListFragment.updateServer(serverStatus);
        if (e != null) {
            if (e instanceof UnknownHostException)
                setActionbarSubtitle("error: unknown host");
            else
                setActionbarSubtitle("error: " + e.getMessage());
        } else {
            setActionbarSubtitle("not connected");
        }
        updateMenuItems(false);
    }

    @Override
    public void onClientEvent(RemoteControl remoteControl, RemoteControl.RpcEvent rpcEvent, Client client, RemoteControl.ClientEvent event) {
        Log.d(TAG, "onClientEvent: " + event.toString());
//        remoteControl.getServerStatus();
/* TODO: group
        if (event == RemoteControl.ClientEvent.deleted)
            serverStatus.removeClient(client);
        else
            serverStatus.updateClient(client);
        sectionsPagerAdapter.updateServer(serverStatus);
*/
        /// update only in case of notifications
        if (rpcEvent == RemoteControl.RpcEvent.response)
            return;

        if (event != RemoteControl.ClientEvent.deleted)
            serverStatus.updateClient(client);
        groupListFragment.updateServer(serverStatus);
    }

    @Override
    public void onServerUpdate(RemoteControl remoteControl, RemoteControl.RpcEvent rpcEvent, ServerStatus serverStatus) {
        this.serverStatus = serverStatus;
        groupListFragment.updateServer(serverStatus);
    }

    @Override
    public void onStreamUpdate(RemoteControl remoteControl, RemoteControl.RpcEvent rpcEvent, Stream stream) {
        // TODO
        serverStatus.updateStream(stream);
        groupListFragment.updateServer(serverStatus);
    }

    @Override
    public void onGroupUpdate(RemoteControl remoteControl, RemoteControl.RpcEvent rpcEvent, Group group) {
        // TODO
        Log.d(TAG, "onGroupUpdate: " + group.toString());
        serverStatus.updateGroup(group);
        groupListFragment.updateServer(serverStatus);
    }


    private void setActionbarSubtitle(final String subtitle) {
        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null)
                    actionBar.setSubtitle(subtitle);
            }
        });
    }

    private void setHost(final String host, final int streamPort, final int controlPort) {
        if (TextUtils.isEmpty(host))
            return;

        this.host = host;
        this.port = streamPort;
        this.controlPort = controlPort;
        Settings.getInstance(this).setHost(host, streamPort, controlPort);
    }

    public void updateMenuItems(final boolean connected) {
        this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (connected) {
                    if (miSettings != null)
                        miSettings.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                    if (miStartStop != null)
                        miStartStop.setVisible(true);
//                    if (miRefresh != null)
//                        miRefresh.setVisible(true);
                } else {
                    if (miSettings != null)
                        miSettings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    if (miStartStop != null)
                        miStartStop.setVisible(false);
//                    if (miRefresh != null)
//                        miRefresh.setVisible(false);
                }
            }
        });
    }


    @Override
    public void onResolved(NsdHelper nsdHelper, NsdServiceInfo serviceInfo) {
        Log.d(TAG, "resolved: " + serviceInfo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            setHost(serviceInfo.getHost().getCanonicalHostName(), serviceInfo.getPort(), serviceInfo.getPort() + 1);
            startRemoteControl();
        }
        NsdHelper.getInstance(this).stopListening();
    }


    @Override
    public void onGroupVolumeChanged(GroupItem groupItem) {
        remoteControl.setGroupVolume(groupItem.getGroup());
    }

    @Override
    public void onMute(GroupItem groupItem, boolean mute) {
        remoteControl.setGroupMuted(groupItem.getGroup(), mute);
    }

    @Override
    public void onVolumeChanged(GroupItem groupItem, ClientItem clientItem, int percent, boolean mute) {
        remoteControl.setVolume(clientItem.getClient(), percent, mute);
    }

    @Override
    public void onDeleteClicked(GroupItem groupItem, final ClientItem clientItem) {
        final Client client = clientItem.getClient();
        client.setDeleted(true);

        serverStatus.updateClient(client);
        groupListFragment.updateServer(serverStatus);
        Snackbar mySnackbar = Snackbar.make(findViewById(R.id.myCoordinatorLayout),
                getString(R.string.client_deleted, client.getVisibleName()),
                Snackbar.LENGTH_SHORT);
        mySnackbar.setAction(R.string.undo_string, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.setDeleted(false);
                serverStatus.updateClient(client);
                groupListFragment.updateServer(serverStatus);
            }
        });
        mySnackbar.setCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar snackbar, int event) {
                super.onDismissed(snackbar, event);
                if (event != DISMISS_EVENT_ACTION) {
                    remoteControl.delete(client);
                    serverStatus.removeClient(client);
                }
            }
        });
        mySnackbar.show();

    }

    @Override
    public void onClientPropertiesClicked(GroupItem groupItem, ClientItem clientItem) {
        Intent intent = new Intent(this, ClientSettingsActivity.class);
        intent.putExtra("client", clientItem.getClient().toJson().toString());
        intent.setFlags(0);
        startActivityForResult(intent, CLIENT_PROPERTIES_REQUEST);
    }

    @Override
    public void onPropertiesClicked(GroupItem groupItem) {
        Intent intent = new Intent(this, GroupSettingsActivity.class);
        intent.putExtra("serverStatus", serverStatus.toJson().toString());
        intent.putExtra("group", groupItem.getGroup().toJson().toString());
        intent.setFlags(0);
        startActivityForResult(intent, GROUP_PROPERTIES_REQUEST);
    }

}

