/*
 * Copyright 2014-2015 Marvin Wißfeld
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.larma.arthook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import de.larma.arthook.instrs.Arm32;
import de.larma.arthook.instrs.InstructionHelper;
import de.larma.arthook.instrs.Thumb2;

import static de.larma.arthook.DebugHelper.logd;
import static de.larma.arthook.DebugHelper.logw;

public final class ArtHook {
    private static final Map<Long, HookPage> pages = new HashMap<>();
    private static InstructionHelper INSTRUCTION_SET_HELPER;

    private ArtHook() {
    }

    static {
        try {
            boolean isArm = true; // TODO: logic
            if (isArm) {
                if ((ArtMethod.of(ArtMethod.class.getDeclaredMethod("of",
                        Method.class)).getEntryPointFromQuickCompiledCode() & 1) == 1) {
                    INSTRUCTION_SET_HELPER = new Thumb2();
                } else {
                    INSTRUCTION_SET_HELPER = new Arm32();
                }
            }
            logd("Using: " + INSTRUCTION_SET_HELPER.getName());
        } catch (Exception ignored) {
        }
    }

    private static HookPage handleHookPage(ArtMethod original, ArtMethod replacement) {
        long originalEntryPoint = INSTRUCTION_SET_HELPER.toMem(
                original.getEntryPointFromQuickCompiledCode());
        if (!pages.containsKey(originalEntryPoint)) {
            pages.put(originalEntryPoint,
                    new HookPage(INSTRUCTION_SET_HELPER, originalEntryPoint));
        }

        HookPage page = pages.get(originalEntryPoint);
        page.addHook(new HookPage.Hook(original, replacement));
        page.update();
        return page;
    }

    public static void hook(Class clazz) {
        Assertions.argumentNotNull(clazz, "clazz");
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Hook.class)) {
                try {
                    hook(method);
                } catch (RuntimeException e) {
                    logw(e);
                }
            }
        }
    }

    public static OriginalMethod hook(Method method) {
        if (!method.isAnnotationPresent(Hook.class))
            throw new IllegalArgumentException("method must have MethodHook annotation");

        Method original;
        try {
            original = findTargetMethod(method);
        } catch (Throwable e) {
            throw new RuntimeException("Can't find original method", e);
        }
        String ident = null;
        if (method.isAnnotationPresent(BackupIdentifier.class)) {
            ident = method.getAnnotation(BackupIdentifier.class).value();
        }
        return hook(original, method, ident);
    }

    public static OriginalMethod hook(Method originalMethod, Method replacementMethod,
                                      String backupIdentifier) {
        ArtMethod backArt = hook(originalMethod, replacementMethod);

        Method backupMethod = backArt.newMethod();
        backupMethod.setAccessible(true);
        OriginalMethod.store(originalMethod, backupMethod, backupIdentifier);

        return new OriginalMethod(backupMethod);
    }

    public static ArtMethod hook(Method originalMethod, Method replacementMethod) {
        Assertions.argumentNotNull(originalMethod, "originalMethod");
        Assertions.argumentNotNull(replacementMethod, "replacementMethod");
        if (originalMethod == replacementMethod || originalMethod.equals(replacementMethod))
            throw new IllegalArgumentException("originalMethod and replacementMethod can't be the" +
                    " same");
        if (!replacementMethod.getReturnType().isAssignableFrom(originalMethod.getReturnType()))
            throw new IllegalArgumentException(
                    "return types of originalMethod and replacementMethod do not match");

        return hook(ArtMethod.of(originalMethod), ArtMethod.of(replacementMethod));
    }

    public static ArtMethod hook(Constructor<?> originalMethod, Method replacementMethod) {
        Assertions.argumentNotNull(originalMethod, "originalMethod");
        Assertions.argumentNotNull(replacementMethod, "replacementMethod");
        if (replacementMethod.getReturnType() != Void.TYPE)
            throw new IllegalArgumentException(
                    "return types of replacementMethod has to be 'void'");

        return hook(ArtMethod.of(originalMethod), ArtMethod.of(replacementMethod));
    }

    private static ArtMethod hook(ArtMethod original, ArtMethod replacement) {
        HookPage page = handleHookPage(original, replacement);

        ArtMethod backArt = original.clone();
        backArt.makePrivate();

        page.activate();
        return backArt;
    }

    static Method findTargetMethod(Method method)
            throws NoSuchMethodException, ClassNotFoundException {
        Hook hook = method.getAnnotation(Hook.class);
        String[] split = hook.value().split("->");
        return findTargetMethod(method, Class.forName(split[0]),
                split.length == 1 ? method.getName() : split[1]);
    }

    private static Method findTargetMethod(Method method, Class<?> targetClass,
                                           String methodName) throws NoSuchMethodException {
        Class<?>[] params = null;
        if (method.getParameterTypes().length > 0) {
            params = new Class<?>[method.getParameterTypes().length - 1];
            System.arraycopy(method.getParameterTypes(), 1, params, 0,
                    method.getParameterTypes().length - 1);
        }
        try {
            Method m = targetClass.getDeclaredMethod(methodName, method.getParameterTypes());
            if (Modifier.isStatic(m.getModifiers())) return m;
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method m = targetClass.getDeclaredMethod(methodName, params);
            if (!Modifier.isStatic(m.getModifiers())) return m;
        } catch (NoSuchMethodException ignored) {
        }
        throw new NoSuchMethodException();
    }
}
