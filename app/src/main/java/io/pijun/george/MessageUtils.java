package io.pijun.george;

import android.content.Context;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.pijun.george.api.OscarAPI;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.OscarError;
import io.pijun.george.api.User;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.event.LocationSharingGranted;
import io.pijun.george.event.LocationSharingRevoked;
import io.pijun.george.models.FriendLocation;
import io.pijun.george.models.FriendRecord;
import io.pijun.george.models.UserRecord;
import retrofit2.Response;

public class MessageUtils {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ERROR_NONE, ERROR_NOT_LOGGED_IN, ERROR_NO_NETWORK, ERROR_UNKNOWN_SENDER, ERROR_REMOTE_INTERNAL,
            ERROR_UNKNOWN, ERROR_INVALID_COMMUNICATION, ERROR_DATABASE_EXCEPTION, ERROR_INVALID_SENDER_ID,
            ERROR_MISSING_CIPHER_TEXT, ERROR_MISSING_NONCE, ERROR_NOT_A_FRIEND, ERROR_DECRYPTION_FAILED,
            ERROR_DATABASE_INCONSISTENCY, ERROR_ENCRYPTION_FAILED
    })
    public @interface Error {}

    public static final int ERROR_NONE = 0;
    public static final int ERROR_NOT_LOGGED_IN = 1;
    public static final int ERROR_NO_NETWORK = 2;
    public static final int ERROR_UNKNOWN_SENDER = 3;
    public static final int ERROR_REMOTE_INTERNAL = 4;
    public static final int ERROR_UNKNOWN = 5;
    public static final int ERROR_INVALID_COMMUNICATION = 6;
    public static final int ERROR_DATABASE_EXCEPTION = 7;
    public static final int ERROR_INVALID_SENDER_ID = 8;
    public static final int ERROR_MISSING_CIPHER_TEXT = 9;
    public static final int ERROR_MISSING_NONCE = 10;
    public static final int ERROR_NOT_A_FRIEND = 11;
    public static final int ERROR_DECRYPTION_FAILED = 12;
    public static final int ERROR_DATABASE_INCONSISTENCY = 13;
    public static final int ERROR_ENCRYPTION_FAILED = 14;

    @WorkerThread
    @Error
    public static int unwrapAndProcess(@NonNull Context context, @NonNull byte[] senderId, @NonNull byte[] cipherText, @NonNull byte[] nonce) {
        L.i("MessageUtils.unwrapAndProcess");
        //noinspection ConstantConditions
        if (senderId == null || senderId.length != Constants.USER_ID_LENGTH) {
            L.i("senderId: " + Hex.toHexString(senderId));
            return ERROR_INVALID_SENDER_ID;
        }
        //noinspection ConstantConditions
        if (cipherText == null) {
            return ERROR_MISSING_CIPHER_TEXT;
        }
        //noinspection ConstantConditions
        if (nonce == null) {
            return ERROR_MISSING_NONCE;
        }
        DB db = DB.get(context);
        UserRecord userRecord = db.getUser(senderId);
        Prefs prefs = Prefs.get(context);
        String token = prefs.getAccessToken();
        KeyPair keyPair = prefs.getKeyPair();
        if (!prefs.isLoggedIn()) {
            return ERROR_NOT_LOGGED_IN;
        }
        if (TextUtils.isEmpty(token) || keyPair == null) {
            return ERROR_NOT_LOGGED_IN;
        }

        if (userRecord == null) {
            L.i("  need to download user");
            // we need to retrieve it from the server
            OscarAPI api = OscarClient.newInstance(token);
            try {
                Response<User> response = api.getUser(Hex.toHexString(senderId)).execute();
                if (!response.isSuccessful()) {
                    OscarError apiErr = OscarError.fromResponse(response);
                    if (apiErr == null) {
                        return ERROR_UNKNOWN;
                    }
                    switch (apiErr.code) {
                        case OscarError.ERROR_INVALID_ACCESS_TOKEN:
                            return ERROR_NOT_LOGGED_IN;
                        case OscarError.ERROR_USER_NOT_FOUND:
                            return ERROR_UNKNOWN_SENDER;
                        case OscarError.ERROR_INTERNAL:
                            return ERROR_REMOTE_INTERNAL;
                        default:
                            return ERROR_UNKNOWN;
                    }
                }
                User user = response.body();
                if (user == null) {
                    FirebaseCrash.log("Unable to decode user " + Hex.toHexString(senderId) + " from response");
                    return ERROR_UNKNOWN;
                }
                // now that we've encountered a new user, add them to the database (because of TOFU)
                userRecord = db.addUser(senderId, user.username, user.publicKey);
                L.i("  added user: " + userRecord);
            } catch (IOException ioe) {
                return ERROR_NO_NETWORK;
            } catch (DB.DBException dbe) {
                FirebaseCrash.report(dbe);
                return ERROR_DATABASE_EXCEPTION;
            }
        }

        byte[] unwrappedBytes = Sodium.publicKeyDecrypt(cipherText, nonce, userRecord.publicKey, keyPair.secretKey);
        if (unwrappedBytes == null) {
            return ERROR_DECRYPTION_FAILED;
        }
        UserComm comm = UserComm.fromJSON(unwrappedBytes);
        if (!comm.isValid()) {
            L.i("usercomm was invalid. here it is: " + comm);
            return ERROR_INVALID_COMMUNICATION;
        }
        L.i("  comm type: " + comm.type);
        switch (comm.type) {
            case LocationSharingGrant:
                L.i("LocationSharingGrant");
                try {
                    db.sharingGrantedBy(userRecord, comm.dropBox);
                } catch (DB.DBException ex) {
                    L.w("error recording location grant", ex);
                    FirebaseCrash.report(ex);
                    return ERROR_DATABASE_EXCEPTION;
                }
                App.postOnBus(new LocationSharingGranted(userRecord.id));
                break;
            case LocationSharingRevocation:
                L.i("LocationSharingRevocation");
                db.sharingRevokedBy(userRecord);
                App.postOnBus(new LocationSharingRevoked(userRecord.id));
                break;
            case LocationInfo:
                FriendRecord fr = db.getFriendByUserId(userRecord.id);
                if (fr == null) {
                    // there should be a friend record for any locations that we receive
                    return ERROR_NOT_A_FRIEND;
                }
                try {
                    db.setFriendLocation(fr.id, comm.latitude, comm.longitude, comm.time, comm.accuracy, comm.speed, comm.bearing);
                } catch (DB.DBException ex) {
                    L.w("error setting location info for friend", ex);
                    FirebaseCrash.report(ex);
                    return ERROR_DATABASE_EXCEPTION;
                }
                App.postOnBus(new FriendLocation(fr.id, comm));
                break;
            case LocationUpdateRequest:
                long updateTime = prefs.getLastLocationUpdateTime();
                // only perform the update if it's been more than 3 minutes since the last one
                long now = System.currentTimeMillis();
                if (now - updateTime > 3 * DateUtils.MINUTE_IN_MILLIS) {
                    L.i("  ok, provide a location update");
                    new LocationSeeker().start(context);
                } else {
                    L.i("  already provided an update at " + updateTime + ". It's " + now + " now");
                }
                break;
        }

        return ERROR_NONE;
    }

}
