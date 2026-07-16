import sys

with open("app/src/main/AndroidManifest.xml", "r") as f:
    content = f.read()

service_tag = """
        <service
            android:name=".service.NotificationReaderService"
            android:label="Hey Manager Notification Reader"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
"""

content = content.replace("</application>", service_tag + "\n    </application>")

with open("app/src/main/AndroidManifest.xml", "w") as f:
    f.write(content)
