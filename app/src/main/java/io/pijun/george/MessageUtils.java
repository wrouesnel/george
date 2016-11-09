package io.pijun.george;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;

import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;

import io.pijun.george.api.OscarAPI;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.OscarError;
import io.pijun.george.api.User;
import io.pijun.george.api.UserComm;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.event.LocationSharingGranted;
import io.pijun.george.event.LocationSharingRequested;
import io.pijun.george.models.FriendLocation;
import io.pijun.george.models.FriendRecord;
import io.pijun.george.models.UserRecord;
import retrofit2.Response;

public class MessageUtils {

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

    @WorkerThread
    public static int unwrapAndProcess(@NonNull Context context, @NonNull byte[] senderId, @NonNull byte[] cipherText, @NonNull byte[] nonce) {
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
        L.i("unwrap from user: " + userRecord);
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
            L.i("|  need to download user");
            // we need to retrieve it from the server
            OscarAPI api = OscarClient.newInstance(token);
            try {
                Response<User> response = api.getUser(Hex.toHexString(senderId)).execute();
                if (!response.isSuccessful()) {
                    OscarError apiErr = OscarError.fromResponse(response);
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
                // now that we've encountered a new user, add them to the database (because of TOFU)

                userRecord = db.addUser(senderId, user.username, user.publicKey);
                L.i("|  added user: " + userRecord);
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
        switch (comm.type) {
            case LocationSharingGrant:
                L.i("LocationSharingGrant");
                try {
                    db.sharingGrantedBy(userRecord.username, comm.dropBox);
                } catch (DB.DBException ex) {
                    L.w("error recording location grant", ex);
                    FirebaseCrash.report(ex);
                    return ERROR_DATABASE_EXCEPTION;
                }
                App.postOnBus(new LocationSharingGranted());
                break;
            case LocationSharingRequest:
                try {
                    // TODO: check if we've already granted sharing to this user, or if we already have a request from them
                    db.addIncomingRequest(userRecord.id, System.currentTimeMillis());
                } catch (DB.DBException ex) {
                    L.w("error recording sharing request", ex);
                    FirebaseCrash.report(ex);
                    return ERROR_DATABASE_EXCEPTION;
                }
                App.postOnBus(new LocationSharingRequested());
                break;
            case LocationSharingRejection:
                break;
            case LocationInfo:
                FriendRecord fr = db.getFriendByUserId(userRecord.id);
                if (fr == null) {
                    // there should be a friend record for any locations that we receive
                    return ERROR_NOT_A_FRIEND;
                }
                try {
                    db.setFriendLocation(fr.id, comm.latitude, comm.longitude, comm.time, comm.accuracy, comm.speed);
                } catch (DB.DBException ex) {
                    L.w("error setting location info for friend", ex);
                    FirebaseCrash.report(ex);
                    return ERROR_DATABASE_EXCEPTION;
                }
                App.postOnBus(new FriendLocation(fr.id, comm));
                break;
        }

        return ERROR_NONE;
    }

}
