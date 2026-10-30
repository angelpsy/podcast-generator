import React, {useState} from 'react';
import {Button, StyleSheet, Text, View} from 'react-native';
import {DarkTheme, DefaultTheme, NavigationContainer, useTheme} from '@react-navigation/native';
import {createNativeStackNavigator, type NativeStackScreenProps} from '@react-navigation/native-stack';
import {createBottomTabNavigator} from '@react-navigation/bottom-tabs';
import {SafeAreaProvider} from 'react-native-safe-area-context';

type RootRoutes = {Tabs: undefined; Details: {id: string}};
type TabRoutes = {Topics: undefined; Podcasts: undefined; Playlists: undefined};
const Stack = createNativeStackNavigator<RootRoutes>();
const Tabs = createBottomTabNavigator<TabRoutes>();

function Panel({children}: {children: React.ReactNode}) {
  const {colors} = useTheme();
  return <View style={[styles.panel, {backgroundColor: colors.background}]}>{children}</View>;
}

function Label({children}: {children: React.ReactNode}) {
  const {colors} = useTheme();
  return <Text style={[styles.text, {color: colors.text}]}>{children}</Text>;
}

function Topics({open, toggleTheme, dark}: {open: () => void; toggleTheme: () => void; dark: boolean}) {
  const [count, setCount] = useState(0);
  return <Panel>
    <Label>Stage 0 · Топики</Label>
    <Label>Счётчик: {count}</Label>
    <Button title="Увеличить счётчик" onPress={() => setCount(value => value + 1)} />
    <Button title="Открыть топик" onPress={open} />
    <Label>Тема: {dark ? 'тёмная' : 'светлая'}</Label>
    <Button title="Переключить тему" onPress={toggleTheme} />
  </Panel>;
}

function Details({route, navigation}: NativeStackScreenProps<RootRoutes, 'Details'>) {
  return <Panel>
    <Label>Экран детали: {route.params.id}</Label>
    <Button title="Ещё один экран" onPress={() => navigation.push('Details', {id: route.params.id + '-next'})} />
    <Button title="Вернуться" onPress={() => navigation.goBack()} />
  </Panel>;
}

export default function StageZeroNavigationProbe() {
  const [dark, setDark] = useState(false);
  return <SafeAreaProvider>
    <NavigationContainer theme={dark ? DarkTheme : DefaultTheme}>
      <Stack.Navigator>
        <Stack.Screen name="Tabs" options={{headerShown: false}}>
          {({navigation}) => <Tabs.Navigator backBehavior="history">
            <Tabs.Screen name="Topics" options={{title: 'Топики'}}>
              {() => <Topics dark={dark} toggleTheme={() => setDark(value => !value)}
                open={() => navigation.navigate('Details', {id: 'compat-topic'})} />}
            </Tabs.Screen>
            <Tabs.Screen name="Podcasts" options={{title: 'Подкасты'}}>
              {() => <Panel><Label>Stage 0 · Подкасты</Label></Panel>}
            </Tabs.Screen>
            <Tabs.Screen name="Playlists" options={{title: 'Плейлисты'}}>
              {() => <Panel><Label>Stage 0 · Плейлисты</Label></Panel>}
            </Tabs.Screen>
          </Tabs.Navigator>}
        </Stack.Screen>
        <Stack.Screen name="Details" component={Details} options={{title: 'Тестовый топик'}} />
      </Stack.Navigator>
    </NavigationContainer>
  </SafeAreaProvider>;
}

const styles = StyleSheet.create({
  panel: {flex: 1, padding: 20, gap: 16},
  text: {fontSize: 18, lineHeight: 26},
});
