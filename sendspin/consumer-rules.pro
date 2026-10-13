# The native bridge looks up these callback methods by name/signature via JNI, so R8 must not
# rename or strip them.
-keep class com.msp1974.sendspin.** { *; }
