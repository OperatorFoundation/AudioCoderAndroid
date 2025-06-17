//
// Created by gamez on 22.02.20.
//

#ifndef LOUD_BANG_JNI_LINK_H
#define LOUD_BANG_JNI_LINK_H
#include <jni.h>

#define WSPR_SYMBOL_LENGTH 8192

#endif //LOUD_BANG_JNI_LINK_H

#ifndef _Included_org_operatorfoundation_audiocoder_CJarInterface
#define _Included_org_operatorfoundation_audiocoder_CJarInterface

extern "C" {

/*
 * Class:     audiocoder_CJarInterface
 * Method:    WSPREncodeToPCM
 * Signature: (Ljava/lang/String;Ljava/lang/String;I)[B
 */
JNIEXPORT jbyteArray JNICALL Java_org_operatorfoundation_audiocoder_CJarInterface_WSPREncodeToPCM
        (JNIEnv *, jclass, jstring, jstring, jint, jint,jboolean);

extern "C"
JNIEXPORT jint JNICALL
Java_org_operatorfoundation_audiocoder_CJarInterface_radioCheck(JNIEnv *env, jclass clazz, jint testvar);


}

#endif