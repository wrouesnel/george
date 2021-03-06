package io.pijun.george.database;

import androidx.annotation.NonNull;

import io.pijun.george.Hex;

public class UserRecord {

    public long id;
    public byte[] userId;
    public String username;
    public byte[] publicKey;

    @NonNull
    @Override
    public String toString() {
        return "UserRecord{" +
                "id=" + id +
                ", userId=" + Hex.toHexString(userId) +
                ", username='" + username + '\'' +
                ", publicKey=" + Hex.toHexString(publicKey) +
                '}';
    }
}
